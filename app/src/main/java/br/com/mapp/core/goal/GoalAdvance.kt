package br.com.mapp.core.goal

import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.Shift
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Meta do dia batida → o lucro das próximas corridas vai para as despesas dos próximos dias
 * (pedido do dono em 26/11/2026). Kotlin puro: quem guarda é overlay/GoalAdvanceStore e quem decide
 * na hora em que a corrida termina é o OverlayService.
 *
 * - Com o lucro líquido do dia (bruto − custo do carro − gastos lançados) na meta, a corrida que
 *   termina não soma mais no lucro do dia: o que ela deixa (valor − % dos custos fixos) fica
 *   **adiantado** para pagar as despesas dos dias seguintes. O bruto continua inteiro à vista.
 * - O desgaste dos km continua saindo do dia: se o lucro cair abaixo da meta, as corridas voltam a
 *   somar no dia até ele passar [REFILL] (meta + 10%); aí voltam a adiantar. Repete quantas vezes for.
 * - Nos dias seguintes, os custos do dia (desgaste + fixos + gastos lançados) saem primeiro do que
 *   foi adiantado; quando ele acaba, voltam a sair do lucro do dia.
 */

/**
 * Corrida cujo lucro foi adiantado para as despesas. [rideAt]/[platform] identificam a corrida na
 * jornada (`ShiftTrip.atMillis` + app): corrida que sai da jornada leva o adiantamento junto.
 * [day] = dia de trabalho (dia epoch) em que ela terminou.
 */
data class Diversion(
    val rideAt: Long,
    val platform: String,
    val day: Long,
    val amount: Double,
)

/** Estado da meta no dia de trabalho [day]: já bateu hoje? Está repondo até meta + 10%? */
data class GoalDayState(
    val day: Long,
    val reached: Boolean = false,
    val refill: Boolean = false,
)

/**
 * Adiantamento visto de um dia: [available] = adiantado nos dias anteriores e ainda não usado (no
 * começo do dia); [used] = custos do dia pagos por ele; [diverted] = lucro de corridas do dia que
 * foi adiantado para os próximos dias.
 */
data class AdvanceDay(
    val available: Double = 0.0,
    val used: Double = 0.0,
    val diverted: Double = 0.0,
) {
    /** Saldo que fica para os próximos dias. */
    val left: Double get() = available - used + diverted
    /** Nada adiantado nem a usar: as telas não mostram a linha do adiantamento. */
    val isEmpty: Boolean get() = available < CENT && diverted < CENT

    private companion object { const val CENT = 0.005 }
}

/** O que fica gravado: corridas adiantadas e o estado da meta do dia de trabalho. */
data class AdvanceBook(
    val diversions: List<Diversion> = emptyList(),
    val state: GoalDayState? = null,
) {
    /** Estado do dia [day] (outro dia = começa do zero). */
    fun stateOf(day: Long): GoalDayState = state?.takeIf { it.day == day } ?: GoalDayState(day)

    /** Primeiro dia com corrida adiantada (null = nenhuma). */
    val firstDay: Long? get() = diversions.minOfOrNull { it.day }

    fun encode(): String = buildString {
        append("v1")
        state?.let { append("\ns|${it.day}|${if (it.reached) 1 else 0}|${if (it.refill) 1 else 0}") }
        diversions.forEach {
            append("\nd|${it.rideAt}|${it.platform}|${it.day}|" + String.format(Locale.ROOT, "%.2f", it.amount))
        }
    }

    companion object {
        /** Lê o que [encode] gravou; linha estragada é ignorada. */
        fun decode(text: String?): AdvanceBook {
            if (text.isNullOrBlank()) return AdvanceBook()
            val lines = text.split('\n')
            if (lines.firstOrNull() != "v1") return AdvanceBook()
            var state: GoalDayState? = null
            val list = mutableListOf<Diversion>()
            lines.drop(1).forEach { line ->
                val p = line.split('|')
                when (p.firstOrNull()) {
                    "s" -> if (p.size == 4) {
                        val day = p[1].toLongOrNull() ?: return@forEach
                        state = GoalDayState(day, p[2] == "1", p[3] == "1")
                    }
                    "d" -> if (p.size == 5) {
                        val at = p[1].toLongOrNull() ?: return@forEach
                        val day = p[3].toLongOrNull() ?: return@forEach
                        val amount = p[4].toDoubleOrNull()?.takeIf { it >= 0 } ?: return@forEach
                        if (p[2].isNotBlank()) list += Diversion(at, p[2], day, amount)
                    }
                }
            }
            return AdvanceBook(list, state)
        }
    }
}

/** Um dia do calendário (dia epoch + intervalo em ms) para somar os custos dele. */
data class DayRange(val day: Long, val from: Long, val to: Long)

object GoalAdvance {
    /** Caiu abaixo da meta: volta a somar no dia até meta × 1,10. */
    const val REFILL = 1.10
    /** Corrida adiantada há mais que isso sai da conta (o saldo já foi usado ou ficou para trás). */
    const val KEEP_DAYS = 62L

    private const val EPS = 0.005

    /** Decisão na hora em que a corrida termina. */
    data class Decision(val divert: Boolean, val state: GoalDayState)

    /**
     * A corrida que termina agora vai para o dia ou para o adiantamento?
     * [netBefore] = lucro líquido do dia antes dela (já com o adiantamento: o que foi adiantado não
     * conta e o que pagou custos do dia conta); [rideNet] = o que ela deixa ([share]).
     * - Meta batida ([netBefore] ≥ meta, ou ≥ meta + 10% repondo) → adianta.
     * - Senão soma no dia. Já bateu hoje e caiu abaixo da meta → passa a repor até meta + 10%.
     */
    fun decide(netBefore: Double, rideNet: Double, goal: Double?, state: GoalDayState): Decision {
        val g = DayGoal.valid(goal) ?: return Decision(false, state)
        val threshold = if (state.refill) g * REFILL else g
        if (netBefore >= threshold - EPS) return Decision(true, state.copy(reached = true, refill = false))
        val dropped = state.reached && netBefore < g - EPS
        val after = netBefore + rideNet
        return Decision(
            false,
            state.copy(reached = state.reached || after >= g - EPS, refill = state.refill || dropped),
        )
    }

    /** O que a corrida deixa para o dia (ou para o adiantamento): valor − % dos custos fixos. */
    fun share(price: Double, fixedShare: Double): Double =
        max(0.0, price * (1 - fixedShare.coerceIn(0.0, 1.0)))

    /** Lucro líquido do dia com o adiantamento: tira o que foi adiantado e põe o que pagou custos. */
    fun net(net: Double, day: AdvanceDay?): Double =
        if (day == null) net else net - day.diverted + day.used

    /** Só as corridas que ainda estão finalizadas em alguma jornada (cancelada/apagada leva junto). */
    fun alive(diversions: List<Diversion>, shifts: List<Shift>): List<Diversion> {
        if (diversions.isEmpty()) return diversions
        val rides = shifts.flatMap { s -> s.trips.filter { it.finished }.map { it.atMillis to it.platform.name } }.toSet()
        return diversions.filter { (it.rideAt to it.platform) in rides }
    }

    /**
     * Adiantamento visto do dia [date]: percorre os dias desde a 1ª corrida adiantada; em cada dia os
     * custos ([costs], dia epoch → R$; dia sem nada = 0) saem primeiro do saldo, e o que foi
     * adiantado no dia entra no saldo só para os dias seguintes.
     */
    fun day(diversions: List<Diversion>, date: Long, costs: Map<Long, Double>): AdvanceDay {
        val byDay = diversions.filter { it.day <= date }.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.amount } }
        val first = byDay.keys.minOrNull() ?: return AdvanceDay()
        var available = 0.0
        var d = first
        while (d < date) {
            val used = min(available, max(0.0, costs[d] ?: 0.0))
            available = available - used + (byDay[d] ?: 0.0)
            d++
        }
        val used = min(available, max(0.0, costs[date] ?: 0.0))
        return AdvanceDay(available, used, byDay[date] ?: 0.0)
    }

    /** Dias anteriores a [date] que entram na conta (desde a 1ª corrida adiantada). */
    fun daysBefore(book: AdvanceBook, date: Long): List<Long> {
        val first = book.firstDay ?: return emptyList()
        return (max(first, date - KEEP_DAYS) until date).toList()
    }

    /**
     * Custos (desgaste + fixos + gastos lançados) de cada dia passado, pela mesma conta das telas
     * ([PeriodMath.totals]). Só jornadas encerradas: a aberta é do dia de trabalho, que não está aqui.
     */
    fun pastCosts(
        days: List<DayRange>,
        history: List<Shift>,
        offers: List<OfferRecord>,
        entries: List<FinanceEntry>,
        costPerKm: Double,
        now: Long,
    ): Map<Long, Double> = days.associate { r ->
        r.day to PeriodMath.totals(r.from, r.to, null, history, offers, entries, costPerKm, now).cost
    }

    /** Grava a corrida adiantada (sem repetir a mesma) e tira as muito antigas. */
    fun add(book: AdvanceBook, d: Diversion, today: Long): AdvanceBook =
        book.copy(
            diversions = (book.diversions.filterNot { it.rideAt == d.rideAt && it.platform == d.platform } + d)
                .filter { it.day >= today - KEEP_DAYS },
        )
}
