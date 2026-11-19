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
import br.com.mapp.core.nav.NavApp
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
 * Atraso da navegação com o motorista **no Waze ou no Google Maps** quando a chamada chegou: a Uber
 * manda a rota direto e a tela dela nem aparece. A tela invisível ([NavLinkActivity]) abre a Uber (igual
 * a tocar no ícone dela — nada é tocado dentro do app) e entrega o pedido aqui; quando o leitor lê a
 * corrida, a rota vai ao app escolhido. Fica fora da Activity porque ela fecha assim que a Uber cobre a tela.
 *
 * Abrir a navegação com o MApp em segundo plano é permitido pelo Android porque o leitor (serviço de
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
     * abre a rota no app escolhido. Pedido anterior ainda esperando é liberado na hora (não se perde rota).
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
            val nav = navApp(appCtx, request)
            TripLog.add(appCtx, NavHold.describeRelease(elapsed, reason, reads, nav) + " · ${app.label} trazido para a frente")
            // O contexto do leitor conectado é o que garante abrir a navegação com o MApp em segundo plano.
            openNav(OfferAccessibilityService.windowContext.value ?: appCtx, nav, link, extras, request)
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

    /** Apps de navegação instalados. */
    fun installedApps(ctx: Context): Set<NavApp> =
        NavApp.entries.filter { ctx.packageManager.getLaunchIntentForPackage(it.packageName) != null }.toSet()

    /**
     * App que recebe a rota da corrida: o escolhido no cartão 🧭 (`OverlayPrefs.navApp`), ou o do próprio
     * link / o outro, se o escolhido não estiver instalado.
     */
    fun navApp(ctx: Context, request: NavRequest?): NavApp =
        NavHold.resolveApp(OverlayPrefs.navApp(ctx), installedApps(ctx), request?.origin ?: NavApp.WAZE)

    /**
     * Abre a rota em [app]: o link como chegou se ele já era desse app, senão o destino convertido
     * ([NavHold.target]). Se não abrir, tenta o outro app e por fim o site do [app] — a navegação não
     * fica sem app. Extras do pedido só vão para o app a que o link se destinava.
     */
    fun openNav(ctx: Context, app: NavApp, link: Uri?, extras: Bundle?, request: NavRequest?): Boolean {
        val origin = request?.origin ?: NavHold.originOf(link?.toString()) ?: NavApp.WAZE
        val order = listOf(app) + NavApp.entries.filter { it != app }
        for (target in order) {
            val uri = if (request != null) NavHold.target(request, target) else link?.toString()?.takeIf { target == origin }
            val sameApp = target == origin && request?.test != true
            if (open(ctx, target, uri, if (sameApp) extras else null)) return true
        }
        if (request != null && !request.test) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NavHold.webUrl(request, app))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // sem navegador: avisa abaixo
            }
        }
        Toast.makeText(ctx.applicationContext, "Nenhum app de navegação encontrado no celular", Toast.LENGTH_LONG).show()
        return false
    }

    /** Abre [uri] no [app] (sem link: só abre o app). false = o app não abriu. */
    private fun open(ctx: Context, app: NavApp, uri: String?, extras: Bundle?): Boolean {
        val out = if (uri != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } else {
            ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        }
        out.setPackage(app.packageName)
        out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            extras?.let { out.putExtras(it) }
        } catch (e: Exception) {
            // Extra que o MApp não consegue ler: segue só com o link.
        }
        return try {
            ctx.startActivity(out)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
