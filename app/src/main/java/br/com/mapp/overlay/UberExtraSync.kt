package br.com.mapp.overlay

import android.content.Context
import android.util.Log
import br.com.mapp.MappApp
import br.com.mapp.core.earnings.DayEarnings
import br.com.mapp.core.earnings.StableExtra
import br.com.mapp.core.earnings.UberExtra
import br.com.mapp.core.model.Platform
import br.com.mapp.core.shift.ShiftMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Ganho extra da Uber: a cada leitura do "Hoje" na tela inicial da Uber ([DayEarnings], via `OfferBus`)
 * compara com as corridas Uber que o MApp contou no dia da Uber e guarda a diferença no lançamento
 * *Ganho extra* do dia (Financeiro → Ganhos e gastos) — um só, atualizado. Regras em `core/earnings/UberExtra`.
 *
 * Roda dentro do copiloto (`OverlayService`): sem ele as corridas não são contadas e a diferença seria
 * a corrida inteira. Com corrida aberta não mexe (a Uber só soma no fim; o MApp marca a corrida aceita).
 * [ridesOpen] = corridas aceitas em andamento no acompanhamento do aceite; [notify] = aviso na tela.
 */
class UberExtraSync(
    private val ctx: Context,
    private val ridesOpen: () -> Int,
    private val notify: (String) -> Unit,
) {
    private val stable = StableExtra()
    private var lastCheckAt = 0L
    private var lastShown = -1.0
    private var lastLogged: String? = null

    /** Main thread (coleta do `OfferBus.dayEarnings`). Erro aqui nunca derruba o copiloto. */
    suspend fun onReading(reading: DayEarnings) {
        try {
            sync(reading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MApp/UberExtra", "Falha ao conferir o ganho extra", e)
        }
    }

    private suspend fun sync(reading: DayEarnings) {
        if (reading.platform != Platform.UBER || !OverlayPrefs.isUberExtraOn(ctx)) return
        val now = System.currentTimeMillis()
        // A árvore é relida a cada evento da tela: confere no máximo a cada CHECK_MS (ou na hora se o valor mudou).
        if (reading.amount == lastShown && now - lastCheckAt < CHECK_MS) return
        lastShown = reading.amount
        lastCheckAt = now

        val current = ShiftTracker.current(ctx).value
        if (ridesOpen() > 0 || current?.trips?.any { !it.finished && it.platform == Platform.UBER } == true) {
            stable.reset()
            return
        }
        val window = UberExtra.uberDay(now, ZoneId.systemDefault())
        val repos = MappApp.repos
        val offers = repos.offers.acceptedBetween(window.from, window.to).first()
        val entries = repos.finance.entries(window.from, window.to).first()
        val check = UberExtra.check(reading.amount, window, current, ShiftTracker.history(ctx).value, offers, entries, now)

        val value = stable.offer(check.target, check.saved, window.from, now) ?: return
        if (!check.differs(value)) return
        val entry = UberExtra.entry(check, value, now)
        if (entry == null) check.kept?.let { repos.finance.delete(it.id) } else repos.finance.save(entry)
        // Duplicados (duas gravações ao mesmo tempo): fica só o primeiro.
        check.extras.filter { it.id != check.kept?.id }.forEach { repos.finance.delete(it.id) }

        val line = "💰 Ganho extra Uber ${ShiftMath.money(check.saved)} → ${ShiftMath.money(value)} · ${UberExtra.note(check)}" +
            if (check.mappAhead > 0) " · MApp ${ShiftMath.money(check.mappAhead)} acima da Uber" else ""
        if (line != lastLogged) {
            lastLogged = line
            TripLog.add(ctx, line)
        }
        if (value > check.saved) {
            notify("💰 Ganho extra Uber: +${ShiftMath.money(UberExtra.round2(value - check.saved))} (Uber hoje ${ShiftMath.money(check.shown)})")
        }
    }

    private companion object {
        const val CHECK_MS = 5_000L
    }
}
