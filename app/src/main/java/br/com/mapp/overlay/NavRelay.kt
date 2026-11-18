package br.com.mapp.overlay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.nav.NavHold
import br.com.mapp.core.nav.NavRequest
import br.com.mapp.core.nav.ReleaseReason
import br.com.mapp.reader.OfferAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Espera o leitor (etapa da corrida lida, leituras sem novidade — [NavHold.release]) ou o tempo
 * máximo e chama [onRelease] uma vez, na main thread. Pede a rajada de leituras ao começar.
 */
internal class NavWaiter(
    private val scope: CoroutineScope,
    private val holdMs: Long,
    private val onRelease: (reason: ReleaseReason, elapsedMs: Long, reads: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val jobs = ArrayList<Job>()
    private var startedAt = 0L
    private var readsAtStart = 0
    private var stage: TripStage? = null
    private var done = false
    private val timeout = Runnable { finish(ReleaseReason.TIMEOUT) }

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        readsAtStart = OfferAccessibilityService.imageReads.value
        OfferAccessibilityService.burst(holdMs)
        handler.postDelayed(timeout, holdMs)
        jobs += scope.launch {
            OfferBus.tripStages.collect { event ->
                if (event.stage == TripStage.ACCEPTED || event.stage == TripStage.ON_TRIP) {
                    stage = event.stage
                    check()
                }
            }
        }
        jobs += scope.launch { OfferAccessibilityService.imageReads.collect { check() } }
    }

    /** Leituras da tela desde o começo da espera. */
    val readsSoFar: Int get() = reads()

    /** Para de esperar sem liberar (a espera passou para outro lugar). */
    fun cancel() {
        if (done) return
        done = true
        handler.removeCallbacks(timeout)
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Libera agora (uma vez só). */
    fun finish(reason: ReleaseReason) {
        if (done) return
        done = true
        handler.removeCallbacks(timeout)
        jobs.forEach { it.cancel() }
        jobs.clear()
        onRelease(reason, elapsed(), reads())
    }

    private fun check() {
        if (done) return
        NavHold.release(elapsed(), holdMs, stage, reads())?.let { finish(it) }
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun reads(): Int = (OfferAccessibilityService.imageReads.value - readsAtStart).coerceAtLeast(0)
}

/**
 * Atraso do Waze com o motorista **no Waze** quando a chamada chegou: a Uber manda a rota direto e a
 * tela dela nem aparece. A tela invisível ([NavLinkActivity]) abre a Uber (igual a tocar no ícone dela —
 * nada é tocado dentro do app) e entrega o pedido aqui; quando o leitor lê a corrida, o mesmo link vai
 * ao Waze. Fica fora da Activity porque ela fecha assim que a Uber cobre a tela.
 *
 * Abrir o Waze com o MApp em segundo plano é permitido pelo Android porque o leitor (serviço de
 * Acessibilidade) está conectado — sem leitor o [NavHold.plan] nem chega a trazer a Uber.
 */
object NavRelay {
    private val scope = MainScope()
    private var waiter: NavWaiter? = null

    /** Última vez que um app de corrida foi trazido para a frente ([SystemClock.elapsedRealtime]; 0 = nunca). */
    @Volatile
    var lastBringAt = 0L
        private set

    /** Tempo desde a última vez que trouxe um app (null = nunca). */
    fun sinceLastBring(): Long? =
        lastBringAt.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }

    /**
     * Chamado logo depois de a Activity abrir [app]: espera até [holdMs] a leitura da corrida e então
     * abre o Waze com [link]. Pedido anterior ainda esperando é liberado na hora (não se perde rota).
     */
    fun holdAfterBringing(
        ctx: Context,
        app: Platform,
        link: Uri?,
        extras: Bundle?,
        request: NavRequest,
        holdMs: Long,
    ) {
        waiter?.finish(ReleaseReason.LEFT)
        val appCtx = ctx.applicationContext
        lastBringAt = SystemClock.elapsedRealtime()
        val next = NavWaiter(scope, holdMs) { reason, elapsed, reads ->
            waiter = null
            TripLog.add(appCtx, NavHold.describeRelease(elapsed, reason, reads) + " · ${app.label} trazido para a frente")
            // O contexto do leitor conectado é o que garante abrir o Waze com o MApp em segundo plano.
            openWaze(OfferAccessibilityService.windowContext.value ?: appCtx, link, extras, request)
        }
        waiter = next
        next.start()
    }

    /** Abre o app de corrida por cima do que está na tela (como tocar no ícone dele). */
    fun bringToFront(ctx: Context, app: Platform): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        return try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Abre o Waze com o mesmo [link] (sem link: só abre o Waze). Waze desinstalado → o mesmo destino no
     * site do Waze. Devolve false se nada abriu.
     */
    fun openWaze(ctx: Context, link: Uri?, extras: Bundle?, request: NavRequest?): Boolean {
        val out = if (link != null) Intent(Intent.ACTION_VIEW, link) else ctx.packageManager.getLaunchIntentForPackage(NavHold.WAZE_PACKAGE)
        if (out != null) {
            out.setPackage(NavHold.WAZE_PACKAGE)
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                extras?.let { out.putExtras(it) }
            } catch (e: Exception) {
                // Extra que o MApp não consegue ler: segue só com o link.
            }
            try {
                ctx.startActivity(out)
                return true
            } catch (e: ActivityNotFoundException) {
                // Waze desinstalado: tenta o site abaixo.
            } catch (e: SecurityException) {
                // idem
            }
        }
        val web = request?.let { Intent(Intent.ACTION_VIEW, Uri.parse(NavHold.webUrl(it))) }
        return try {
            if (web == null) throw ActivityNotFoundException()
            ctx.startActivity(web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Toast.makeText(ctx.applicationContext, "Waze não encontrado no celular", Toast.LENGTH_LONG).show()
            false
        }
    }
}
