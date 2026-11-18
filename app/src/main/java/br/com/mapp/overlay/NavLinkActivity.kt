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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.model.Platform
import br.com.mapp.core.nav.NavApp
import br.com.mapp.core.nav.NavHold
import br.com.mapp.core.nav.NavLinkState
import br.com.mapp.core.nav.NavRequest
import br.com.mapp.core.nav.ReleaseReason
import br.com.mapp.reader.OfferAccessibilityService
import br.com.mapp.reader.ReaderStatus

/**
 * Atraso do Waze ([NavHold]). Com o MApp escolhido como app dos links `waze://`, a Uber abre esta tela
 * em vez do Waze. Ela é **invisível e não recebe toques** (a Uber continua à vista e respondendo ao
 * dedo).
 *
 * - **Uber na tela:** pede ao leitor uma rajada de leituras e, assim que ele reconhece a corrida (ou
 *   depois de algumas leituras sem novidade, ou no tempo máximo), abre o Waze com o mesmo link e fecha.
 * - **Motorista no Waze** (a Uber mandou a rota com outro app na frente): abre a Uber — igual a tocar no
 *   ícone dela — e passa a espera para o [NavRelay], que abre o Waze depois da leitura.
 *
 * Só atrasa a abertura que a Uber pediu: não toca em nada nem muda o destino. Desligada no manifest
 * (`enabled=false`); só existe para o Android quando o motorista liga o atraso na tela inicial.
 */
class NavLinkActivity : ComponentActivity() {

    private var link: Uri? = null
    private var request: NavRequest? = null
    private var forwarded = false
    private var waiter: NavWaiter? = null
    private var nav: NavApp = NavApp.WAZE
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparente por inteiro (alpha 0) e sem toques: o Android deixa o dedo passar para a Uber.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setDimAmount(0f)
        window.attributes = window.attributes.also { it.alpha = 0f }
        noAnimation(open = true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = release(ReleaseReason.LEFT)
        })

        link = intent?.data
        nav = NavRelay.navApp(this)
        val req = NavHold.parse(link?.toString())
        request = req
        if (req == null || savedInstanceState != null) {
            forward(null)
            return
        }
        val referrer = callerPackage()
        val enabled = OverlayPrefs.isNavHoldOn(this)
        val readerOn = ReaderStatus.state.value.connected
        val plan = NavHold.plan(
            enabled = enabled,
            delayMs = OverlayPrefs.navHoldMs(this),
            copilotOn = OfferBus.hasListeners,
            readerOn = readerOn,
            referrer = referrer,
            ownPackage = packageName,
            test = req.test,
            rideAppOnScreen = readerOn && OfferAccessibilityService.rideAppOnScreen() != null,
            bringOn = OverlayPrefs.isNavBringOn(this),
            lastRideApp = OfferAccessibilityService.lastRideApp,
            sinceLastBringMs = NavRelay.sinceLastBring(),
        )
        TripLog.add(this, NavHold.describe(req, referrer, plan, nav))
        if (plan.holdMs <= 0L) {
            forward(null)
            return
        }

        val bring = plan.bring
        if (bring != null) {
            if (NavRelay.bringToFront(this, bring)) {
                // A Uber vai cobrir esta tela (que fecha): a espera continua no NavRelay.
                NavRelay.holdAfterBringing(this, bring, link, extrasOrNull(), req, plan.holdMs)
                forwarded = true
                finish()
                noAnimation(open = false)
                return
            }
            TripLog.add(this, "🧭 Não consegui abrir o app ${bring.label}: Waze aberto na hora")
            forward(null)
            return
        }

        waiter = NavWaiter(lifecycleScope, plan.holdMs) { reason, elapsed, reads ->
            TripLog.add(this, NavHold.describeRelease(elapsed, reason, reads, nav))
            forward(reason)
        }.also { it.start() }
        // A janela de aceite da Uber pode fechar logo depois de pedir o Waze (com o Waze por baixo):
        // confere de novo e, se a Uber sumiu, traz o app para ler.
        plan.fallback?.let { app -> handler.postDelayed({ lateCheck(app, req, plan.holdMs) }, NavHold.LATE_CHECK_MS) }
    }

    private fun lateCheck(app: Platform, req: NavRequest, holdMs: Long) {
        val w = waiter ?: return
        if (forwarded) return
        if (!NavHold.lateBring(w.readsSoFar, OfferAccessibilityService.rideAppOnScreen() != null)) return
        if (!NavRelay.bringToFront(this, app)) return
        w.cancel()
        waiter = null
        forwarded = true
        TripLog.add(this, "🧭 ${app.label} saiu da tela logo depois de pedir o Waze: trazendo o app para ler")
        NavRelay.holdAfterBringing(this, app, link, extrasOrNull(), req, holdMs + NavHold.BRING_EXTRA_MS - NavHold.LATE_CHECK_MS)
        finish()
        noAnimation(open = false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Tela apagada, botão início ou outro app por cima: abre o Waze agora (não fica preso aqui).
        if (!forwarded && !isChangingConfigurations) release(ReleaseReason.LEFT)
    }

    /** Termina a espera (o [NavWaiter] chama [forward]); sem espera em curso, abre o Waze direto. */
    private fun release(reason: ReleaseReason) {
        val w = waiter
        if (w != null) w.finish(reason) else forward(reason)
    }

    /** Abre o Waze com o mesmo link (uma vez só) e fecha. [reason] null = sem espera. */
    private fun forward(reason: ReleaseReason?) {
        if (forwarded) return
        forwarded = true
        NavRelay.openNav(this, nav, link, extrasOrNull(), request)
        if (request?.test == true) {
            Toast.makeText(this, "✅ O link do Waze passou pelo MApp e foi entregue ao ${nav.label}.", Toast.LENGTH_LONG).show()
        }
        finish()
        noAnimation(open = false)
    }

    private fun extrasOrNull(): Bundle? = try {
        intent?.extras
    } catch (e: Exception) {
        null
    }

    /** Pacote do app que pediu o Waze (a Uber, normalmente); null = não se identificou. */
    private fun callerPackage(): String? =
        callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host

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
