package br.com.mapp.overlay

import android.content.Context
import br.com.mapp.core.copilot.OnlineState
import br.com.mapp.core.copilot.RideOnline
import br.com.mapp.core.model.Platform
import br.com.mapp.core.shift.OfflineMark
import br.com.mapp.core.shift.OfflinePause
import br.com.mapp.core.shift.OfflineStep
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.finance.Format

/**
 * Pausa a jornada sozinha depois de X minutos offline na Uber (tela inicial → *Pausar a jornada
 * offline*) e retoma quando fica online de novo. Regras em `core/shift/OfflinePause`.
 *
 * Roda dentro do copiloto (`OverlayService`): sem ele a jornada já fica pausada. Tudo na main thread.
 * [ridesOpen] = corridas aceitas em andamento no acompanhamento do aceite; [notify] = aviso na tela.
 */
class OfflineShiftPause(
    private val ctx: Context,
    private val ridesOpen: () -> Int,
    private val notify: (String) -> Unit,
) {
    private var lastState: OnlineState? = null
    private var mark: OfflineMark? = null

    /** Coleta do `OfferBus.rideOnline`: online/offline lido na tela inicial da Uber. */
    fun onOnline(reading: RideOnline) {
        if (reading.platform != Platform.UBER) return
        val was = lastState
        lastState = reading.state
        val now = System.currentTimeMillis()
        when (reading.state) {
            OnlineState.ONLINE -> {
                mark = null
                resumeIfPaused()
            }
            OnlineState.OFFLINE -> {
                val minutes = OverlayPrefs.offlinePauseMinutes(ctx)
                // Só a troca online → offline marca: ▶ à mão estando offline não pausa de novo.
                if (was == OnlineState.ONLINE && minutes > OfflinePause.OFF) {
                    mark = OfflinePause.mark(ShiftTracker.current(ctx).value, now)
                    if (mark != null) TripLog.add(ctx, "⏸ Uber offline: a jornada pausa sozinha em $minutes min se continuar offline")
                }
                tick(now)
            }
        }
    }

    /** Oferta lida (de qualquer app): o motorista está trabalhando, o prazo recomeça. */
    fun onActivity() {
        if (mark == null) return
        mark = OfflinePause.mark(ShiftTracker.current(ctx).value, System.currentTimeMillis())
    }

    /** A cada ~15 s com o copiloto ligado (o motorista pode fechar a Uber depois de ficar offline). */
    fun tick(now: Long = System.currentTimeMillis()) {
        val m = mark ?: return
        val shift = ShiftTracker.current(ctx).value
        val rideOpen = ridesOpen() > 0 || shift?.pendingTrip != null
        when (val step = OfflinePause.step(shift, m, OverlayPrefs.offlinePauseMinutes(ctx), rideOpen, now)) {
            OfflineStep.Wait -> Unit
            OfflineStep.Drop -> mark = null
            is OfflineStep.Restart -> mark = step.mark
            is OfflineStep.Pause -> {
                mark = null
                if (!ShiftTracker.pauseAt(ctx, m)) return
                OverlayPrefs.setOfflinePausedShift(ctx, step.shift.startedAt)
                val at = Format.time(m.since)
                val minutes = ShiftMath.num(((now - m.since) / 60_000.0), 0)
                notify("⏸ Jornada pausada sozinha: offline na Uber desde $at. Volta sozinha quando você ficar online")
                TripLog.add(ctx, "⏸ Jornada pausada sozinha: offline desde $at ($minutes min); tempo e km contam até $at")
            }
        }
    }

    /** Jornada mudou: retomada ou encerrada por outro caminho apaga a marca de "pausada por offline". */
    fun onShiftChanged(running: Boolean, exists: Boolean) {
        if ((running || !exists) && OverlayPrefs.offlinePausedShift(ctx) != 0L) OverlayPrefs.setOfflinePausedShift(ctx, 0L)
    }

    private fun resumeIfPaused() {
        val paused = OverlayPrefs.offlinePausedShift(ctx)
        if (!OfflinePause.resumeOnOnline(ShiftTracker.current(ctx).value, paused)) return
        OverlayPrefs.setOfflinePausedShift(ctx, 0L)
        ShiftTracker.start(ctx)
        notify("▶ Jornada retomada sozinha: você ficou online")
        TripLog.add(ctx, "▶ Uber online: jornada retomada sozinha (tinha pausado por offline)")
    }
}
