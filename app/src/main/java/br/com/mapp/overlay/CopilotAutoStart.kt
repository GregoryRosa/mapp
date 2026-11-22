package br.com.mapp.overlay

import android.content.Context
import br.com.mapp.Gate
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.copilot.AutoStart
import br.com.mapp.core.copilot.AutoStartAction
import br.com.mapp.core.copilot.RideOnline
import br.com.mapp.reader.OfferAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Copiloto automático: Uber aberta com "Você está online" na tela → o copiloto liga sozinho.
 *
 * Fica fora do [OverlayService] porque é justamente quando ele está parado que precisa agir: escuta o
 * [OfferBus.rideOnline] desde a abertura do processo (`MappApp`), que existe enquanto o leitor de
 * Acessibilidade está conectado. As regras (pausa depois de parar à mão, intervalo entre tentativas)
 * ficam em `core/copilot/AutoStart`.
 */
object CopilotAutoStart {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    /** Última tentativa de ligar sozinho (relógio do aparelho). */
    private var lastTryAt = 0L

    /** Chamado uma vez em `MappApp.onCreate`. */
    fun watch(app: Context) {
        if (started) return
        started = true
        val ctx = app.applicationContext
        scope.launch { OfferBus.rideOnline.collect { onOnline(ctx, it) } }
    }

    /** Main thread. */
    private fun onOnline(ctx: Context, event: RideOnline) {
        val now = System.currentTimeMillis()
        val action = AutoStart.decide(
            enabled = OverlayPrefs.isAutoStartOn(ctx),
            state = event.state,
            copilotRunning = OverlayService.running.value,
            snoozedAt = OverlayPrefs.autoStartSnoozedAt(ctx),
            lastTryAt = lastTryAt,
            now = now,
        )
        when (action) {
            AutoStartAction.NONE -> Unit
            AutoStartAction.WAKE -> {
                OverlayPrefs.setAutoStartSnoozedAt(ctx, 0L)
                TripLog.add(ctx, "▷ ${event.platform.label} offline: o copiloto volta a ligar sozinho quando ficar online")
            }
            AutoStartAction.START -> {
                // "Aberto" = a tela da Uber na frente (não só a bolha dela por cima de outro app).
                if (OfferAccessibilityService.rideAppOnScreen() != event.platform) return
                lastTryAt = now
                // Sem assinatura o serviço recusaria com aviso — aqui fica quieto (não insiste a cada tela).
                if (!Gate.isOpen(ctx)) return
                OverlayService.startAuto(ctx, event.platform)
            }
        }
    }
}
