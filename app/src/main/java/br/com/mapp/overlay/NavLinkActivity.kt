package br.com.mapp.overlay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.nav.NavHold
import br.com.mapp.core.nav.NavLinkState
import br.com.mapp.core.nav.NavRequest
import br.com.mapp.core.nav.ReleaseReason
import br.com.mapp.reader.OfferAccessibilityService
import br.com.mapp.reader.ReaderStatus
import kotlinx.coroutines.launch

/**
 * Atraso do Waze ([NavHold]). Com o MApp escolhido como app dos links `waze://`, a Uber abre esta tela
 * em vez do Waze. Ela é **invisível e não recebe toques** (a Uber continua à vista e respondendo ao
 * dedo), pede ao leitor uma rajada de leituras da tela e, assim que ele reconhece a corrida (ou depois
 * de algumas leituras sem novidade, ou no tempo máximo), abre o Waze com o mesmo link e fecha.
 *
 * Só atrasa a abertura que a Uber pediu: não toca em nada nem muda o destino. Desligada no manifest
 * (`enabled=false`); só existe para o Android quando o motorista liga o atraso na tela inicial.
 */
class NavLinkActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var link: Uri? = null
    private var request: NavRequest? = null
    private var forwarded = false
    private var startedAt = 0L
    private var holdMs = 0L
    private var readsAtStart = 0
    private var stage: TripStage? = null

    private val timeout = Runnable { forward(ReleaseReason.TIMEOUT) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparente por inteiro (alpha 0) e sem toques: o Android deixa o dedo passar para a Uber.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setDimAmount(0f)
        window.attributes = window.attributes.also { it.alpha = 0f }
        noAnimation(open = true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = forward(ReleaseReason.LEFT)
        })

        link = intent?.data
        val req = NavHold.parse(link?.toString())
        request = req
        if (req == null || savedInstanceState != null) {
            forward(null)
            return
        }
        val referrer = callerPackage()
        val plan = NavHold.plan(
            enabled = OverlayPrefs.isNavHoldOn(this),
            delayMs = OverlayPrefs.navHoldMs(this),
            copilotOn = OfferBus.hasListeners,
            readerOn = ReaderStatus.state.value.connected,
            referrer = referrer,
            ownPackage = packageName,
            test = req.test,
        )
        TripLog.add(this, NavHold.describe(req, referrer, plan))
        if (plan.holdMs <= 0L) {
            forward(null)
            return
        }

        holdMs = plan.holdMs
        startedAt = SystemClock.elapsedRealtime()
        readsAtStart = OfferAccessibilityService.imageReads.value
        OfferAccessibilityService.burst(plan.holdMs)
        handler.postDelayed(timeout, plan.holdMs)
        lifecycleScope.launch {
            OfferBus.tripStages.collect { event ->
                if (event.stage == TripStage.ACCEPTED || event.stage == TripStage.ON_TRIP) {
                    stage = event.stage
                    check()
                }
            }
        }
        lifecycleScope.launch { OfferAccessibilityService.imageReads.collect { check() } }
    }

    override fun onStop() {
        super.onStop()
        // Tela apagada, botão início ou outro app por cima: abre o Waze agora (não fica preso aqui).
        if (!forwarded && !isChangingConfigurations) forward(ReleaseReason.LEFT)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun check() {
        if (forwarded || holdMs <= 0L) return
        val reason = NavHold.release(elapsed(), holdMs, stage, reads()) ?: return
        forward(reason)
    }

    /** Abre o Waze com o mesmo link (uma vez só) e fecha. [reason] null = sem espera. */
    private fun forward(reason: ReleaseReason?) {
        if (forwarded) return
        forwarded = true
        handler.removeCallbacksAndMessages(null)
        if (reason != null) TripLog.add(this, NavHold.describeRelease(elapsed(), reason, reads()))
        openWaze()
        if (request?.test == true) {
            Toast.makeText(this, "✅ O link do Waze passou pelo MApp e foi entregue ao Waze.", Toast.LENGTH_LONG).show()
        }
        finish()
        noAnimation(open = false)
    }

    private fun openWaze() {
        val uri = link
        val out = if (uri != null) Intent(Intent.ACTION_VIEW, uri) else packageManager.getLaunchIntentForPackage(NavHold.WAZE_PACKAGE)
        if (out != null) {
            out.setPackage(NavHold.WAZE_PACKAGE)
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                intent?.extras?.let { out.putExtras(it) }
            } catch (e: Exception) {
                // Extra que o MApp não consegue ler: segue só com o link.
            }
            try {
                startActivity(out)
                return
            } catch (e: ActivityNotFoundException) {
                // Waze desinstalado: tenta o site abaixo.
            } catch (e: SecurityException) {
                // idem
            }
        }
        val web = request?.let { Intent(Intent.ACTION_VIEW, Uri.parse(NavHold.webUrl(it))) }
        try {
            if (web == null) throw ActivityNotFoundException()
            startActivity(web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Waze não encontrado no celular", Toast.LENGTH_LONG).show()
        }
    }

    /** Pacote do app que pediu o Waze (a Uber, normalmente); null = não se identificou. */
    private fun callerPackage(): String? =
        callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host

    private fun elapsed(): Long = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    private fun reads(): Int = (OfferAccessibilityService.imageReads.value - readsAtStart).coerceAtLeast(0)

    private fun noAnimation(open: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                if (open) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        /** Liga/desliga o atalho no Android: desligado, o MApp nem aparece no "Abrir com" do Waze. */
        fun setEnabled(ctx: Context, on: Boolean) {
            val component = ComponentName(ctx, NavLinkActivity::class.java)
            val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val pm = ctx.packageManager
            try {
                if (pm.getComponentEnabledSetting(component) != wanted) {
                    pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
                }
            } catch (e: Exception) {
                // sem efeito: o cartão continua mostrando a situação real
            }
        }

        /** Deixa o atalho do Android igual ao que está salvo no MApp. */
        fun sync(ctx: Context) = setEnabled(ctx, OverlayPrefs.isNavHoldOn(ctx))

        /** Situação atual (cartão da tela inicial). */
        fun state(ctx: Context): NavLinkState {
            val pm = ctx.packageManager
            val installed = pm.getLaunchIntentForPackage(NavHold.WAZE_PACKAGE) != null
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse(NavHold.PROBE_URI))
            val handler = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.resolveActivity(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
                }?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            }
            return NavHold.state(OverlayPrefs.isNavHoldOn(ctx), installed, handler, ctx.packageName)
        }

        /** Botão Testar: o Android pergunta quem abre o link (escolha MApp → Sempre). */
        fun test(ctx: Context) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NavHold.TEST_URI)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(ctx, "Nenhum app abre links do Waze", Toast.LENGTH_LONG).show()
            }
        }

        /** Tela do Waze nas Configurações (Abrir por padrão → Limpar padrões). */
        fun openWazeSettings(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${NavHold.WAZE_PACKAGE}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Toast.makeText(ctx, "Abra Configurações → Apps → Waze → Abrir por padrão", Toast.LENGTH_LONG).show()
            }
        }
    }
}
