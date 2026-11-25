package br.com.mapp.core.shift

import br.com.mapp.core.model.KmBase
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * R$ por corrida dos custos fixos no dia de trabalho [day], com as contas que levaram a ele.
 * [perRide] = [remaining] ÷ [ridesLeft].
 */
data class FixedToday(
    val perRide: Double,
    /** O que o mês tem de pagar: o total dos custos fixos (no mês em que o rateio começou, só a parte que faltava). */
    val monthTotal: Double,
    /** Já descontado pelas corridas dos dias anteriores do mês (cada jornada encerrada guarda o seu). */
    val paid: Double,
    /** Corridas que faltam no mês, contando as de hoje (estimativa pela base de Custos fixos). */
    val ridesLeft: Double,
    /** Corridas finalizadas hoje (jornadas do dia de trabalho). */
    val ridesToday: Int,
    /** Dias do calendário até o fim do mês, contando hoje. */
    val daysLeft: Int,
    val day: LocalDate,
    /** Dia em que o rateio começou, se foi neste mês (o mês paga só a parte daí até o fim). */
    val startedOn: LocalDate? = null,
) {
    /** Falta pagar no mês (antes das corridas de hoje). */
    val remaining: Double get() = max(0.0, monthTotal - paid)
    val monthEnd: LocalDate get() = YearMonth.from(day).atEndOfMonth()
}

/**
 * Rateio dos custos fixos do mês (seguro, financiamento...) pelas corridas finalizadas das jornadas,
 * para a soma do mês **bater com o total** de *Custos fixos*:
 *
 * **R$ por corrida de hoje = o que falta pagar no mês ÷ corridas que faltam no mês.**
 * - *Falta pagar*: total do mês − o que as corridas dos dias anteriores já descontaram. Cada jornada
 *   encerrada guarda o R$ por corrida do seu dia ([Shift.fixedPerRide]), que não muda depois — o
 *   passado fica como está e a diferença vai para as corridas que ainda vêm.
 * - *Corridas que faltam*: corridas por dia × (hoje + os dias rodados que ainda vêm no mês, pela
 *   proporção de dias rodados da base). Rodou menos que o previsto → o valor sobe; rodou mais → desce.
 *   Se hoje já passou disso, divide pelas de hoje: o dia nunca desconta mais do que falta.
 * - **Mês encerrado** ([settle]): o último dia rodado fecha a conta — o que faltava ÷ corridas daquele
 *   dia —, então a soma do mês é exatamente o total.
 *
 * Todas as corridas de um dia usam o mesmo valor (as jornadas encerradas do dia acompanham as mudanças).
 * Corridas fora de jornada não entram no rateio: a média de corridas por dia também é só das jornadas.
 */
object FixedPlan {
    /** Dia de trabalho: o da jornada aberta (mesmo que tenha começado ontem) ou hoje. */
    fun workDate(current: Shift?, now: Long, zone: ZoneId): LocalDate =
        dateOf(current?.startedAt?.coerceAtMost(now) ?: now, zone)

    /**
     * Quanto o mês [month] paga dos custos fixos [total]: tudo, ou, no mês em que o rateio começou
     * ([since] depois do dia 1), só a parte dos dias de [since] até o fim. Mês antes do início = 0.
     */
    fun monthTarget(total: Double, month: YearMonth, since: LocalDate?): Double {
        if (since == null) return total
        val start = YearMonth.from(since)
        return when {
            month < start -> 0.0
            month > start -> total
            else -> total * (month.lengthOfMonth() - since.dayOfMonth + 1) / month.lengthOfMonth()
        }
    }

    /**
     * Dia em que o rateio começa (1ª vez com custos fixos): o 1º dia do mês de trabalho em que uma
     * jornada encerrada já descontou custo fixo (versão anterior, que dividia pelo mês todo) ou hoje.
     */
    fun startDate(history: List<Shift>, current: Shift?, now: Long, zone: ZoneId): LocalDate {
        val day = workDate(current, now, zone)
        val first = day.withDayOfMonth(1)
        return history
            .filter { (it.fixedPerRide ?: 0.0) > 0.0 && finished(it) > 0 }
            .map { dateOf(it.startedAt, zone) }
            .filter { it >= first && it <= day }
            .minOrNull() ?: day
    }

    /**
     * R$ por corrida do dia de trabalho. [total] = custos fixos do mês; [base] = corridas por dia e dias
     * rodados no mês; [history] = jornadas encerradas; [current] = a aberta; [since] = início do rateio.
     */
    fun today(
        total: Double,
        base: KmBase,
        history: List<Shift>,
        current: Shift?,
        now: Long,
        zone: ZoneId,
        since: LocalDate? = null,
    ): FixedToday {
        val day = workDate(current, now, zone)
        val month = YearMonth.from(day)
        val target = monthTarget(total, month, since)
        val first = maxOf(month.atDay(1), since ?: month.atDay(1))
        val paid = history
            .filter { val d = dateOf(it.startedAt, zone); d >= first && d < day }
            .sumOf { finished(it) * charged(it) }
        val ridesToday = history.filter { dateOf(it.startedAt, zone) == day }.sumOf { finished(it) } +
            (current?.let { finished(it) } ?: 0)
        val daysLeft = (ChronoUnit.DAYS.between(day, month.atEndOfMonth()) + 1).toInt()
        // Dias rodados que ainda vêm: a proporção de dias rodados da base sobre os dias que faltam.
        val share = min(1.0, base.daysPerMonth / month.lengthOfMonth())
        val expected = if (base.monthRides > 0) base.ridesPerDay * (1 + share * (daysLeft - 1)) else 0.0
        val ridesLeft = if (expected > 0) max(expected, ridesToday.toDouble()) else 0.0
        val remaining = max(0.0, target - paid)
        return FixedToday(
            perRide = if (ridesLeft > 0) remaining / ridesLeft else 0.0,
            monthTotal = target,
            paid = paid,
            ridesLeft = ridesLeft,
            ridesToday = ridesToday,
            daysLeft = daysLeft,
            day = day,
            startedOn = since?.takeIf { YearMonth.from(it) == month && it.dayOfMonth > 1 },
        )
    }

    /**
     * Fecha o mês [month] (já encerrado): as jornadas do último dia com corrida finalizada passam a
     * descontar o que faltava pagar ÷ corridas daquele dia, e a soma do mês fica igual ao [total]
     * (ou à parte dele, no mês em que o rateio começou). Sem corrida no mês, nada muda.
     */
    fun settle(history: List<Shift>, total: Double, month: YearMonth, zone: ZoneId, since: LocalDate? = null): List<Shift> {
        val target = monthTarget(total, month, since)
        val first = maxOf(month.atDay(1), since ?: month.atDay(1))
        val last = month.atEndOfMonth()
        val inMonth = history.filter { val d = dateOf(it.startedAt, zone); d >= first && d <= last }
        val lastDay = inMonth.filter { finished(it) > 0 }.maxOfOrNull { dateOf(it.startedAt, zone) } ?: return history
        val paid = inMonth.filter { dateOf(it.startedAt, zone) < lastDay }.sumOf { finished(it) * charged(it) }
        val rides = inMonth.filter { dateOf(it.startedAt, zone) == lastDay }.sumOf { finished(it) }
        val perRide = max(0.0, target - paid) / rides
        return history.map { if (dateOf(it.startedAt, zone) == lastDay) it.copy(fixedPerRide = perRide) else it }
    }

    private fun finished(s: Shift): Int = s.trips.count { it.finished }

    /** R$ por corrida que a jornada encerrada descontou (antes dos custos fixos por corrida = 0). */
    private fun charged(s: Shift): Double = s.fixedPerRide ?: 0.0

    private fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
