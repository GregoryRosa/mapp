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
 * Porcentagem do valor das corridas que paga os custos fixos no dia de trabalho [day], com as contas
 * que levaram a ela. [share] = [remaining] ÷ [grossLeft] (fração: 0,25 = 25%).
 */
data class FixedToday(
    val share: Double,
    /** O que o mês tem de pagar: o total dos custos fixos (no mês em que o rateio começou, só a parte que faltava). */
    val monthTotal: Double,
    /** Já descontado pelas corridas dos dias anteriores do mês (cada jornada encerrada guarda a sua %). */
    val paid: Double,
    /** Faturamento (R$ bruto das corridas) que ainda deve entrar no mês, contando o de hoje (estimativa pela base). */
    val grossLeft: Double,
    /** Bruto das corridas finalizadas hoje (jornadas do dia de trabalho). */
    val grossToday: Double,
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
 * Rateio dos custos fixos do mês (seguro, financiamento...) pelo **valor** das corridas finalizadas das
 * jornadas, para a soma do mês **bater com o total** de *Custos fixos*. Cada corrida paga a mesma
 * porcentagem do seu valor: a de R$ 12 paga menos que a de R$ 50.
 *
 * **% de hoje = o que falta pagar no mês ÷ faturamento que ainda deve entrar no mês.**
 * - *Falta pagar*: total do mês − o que as corridas dos dias anteriores já descontaram. Cada jornada
 *   encerrada guarda a % do seu dia ([Shift.fixedShare]; as antigas, o R$ por corrida de
 *   [Shift.fixedPerRide]), que não muda depois — o passado fica como está e a diferença vai para as
 *   corridas que ainda vêm.
 * - *Faturamento que falta*: bruto por dia × (hoje + os dias rodados que ainda vêm no mês, pela
 *   proporção de dias rodados da base). Faturou menos que o previsto → a % sobe; mais → desce. Se hoje
 *   já faturou mais que isso, divide pelo bruto de hoje: o dia nunca desconta mais do que falta.
 * - **Mês encerrado** ([settle]): o último dia rodado fecha a conta — o que faltava ÷ bruto daquele
 *   dia —, então a soma do mês é exatamente o total.
 *
 * Todas as corridas de um dia usam a mesma % (as jornadas encerradas do dia acompanham as mudanças).
 * Corridas fora de jornada não entram no rateio: o bruto médio por dia também é só das jornadas.
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
     * jornada encerrada já descontou custo fixo (por corrida, nas versões anteriores, ou em %) ou hoje.
     */
    fun startDate(history: List<Shift>, current: Shift?, now: Long, zone: ZoneId): LocalDate {
        val day = workDate(current, now, zone)
        val first = day.withDayOfMonth(1)
        return history
            .filter { ((it.fixedPerRide ?: 0.0) > 0.0 || (it.fixedShare ?: 0.0) > 0.0) && finished(it) > 0 }
            .map { dateOf(it.startedAt, zone) }
            .filter { it >= first && it <= day }
            .minOrNull() ?: day
    }

    /**
     * % do valor das corridas do dia de trabalho. [total] = custos fixos do mês; [base] = bruto por dia
     * e dias rodados no mês; [history] = jornadas encerradas; [current] = a aberta; [since] = início do
     * rateio. Sem faturamento na base ([KmBase.monthGross] = 0) não divide: 0%.
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
            .sumOf { charged(it) }
        val grossToday = history.filter { dateOf(it.startedAt, zone) == day }.sumOf { it.finishedGross } +
            (current?.finishedGross ?: 0.0)
        val daysLeft = (ChronoUnit.DAYS.between(day, month.atEndOfMonth()) + 1).toInt()
        // Dias rodados que ainda vêm: a proporção de dias rodados da base sobre os dias que faltam.
        val workRatio = min(1.0, base.daysPerMonth / month.lengthOfMonth())
        val expected = if (base.monthGross > 0) base.grossPerDay * (1 + workRatio * (daysLeft - 1)) else 0.0
        val grossLeft = if (expected > 0) max(expected, grossToday) else 0.0
        val remaining = max(0.0, target - paid)
        return FixedToday(
            share = if (grossLeft > 0) remaining / grossLeft else 0.0,
            monthTotal = target,
            paid = paid,
            grossLeft = grossLeft,
            grossToday = grossToday,
            daysLeft = daysLeft,
            day = day,
            startedOn = since?.takeIf { YearMonth.from(it) == month && it.dayOfMonth > 1 },
        )
    }

    /**
     * Fecha o mês [month] (já encerrado): as jornadas do último dia com faturamento passam a descontar
     * o que faltava pagar ÷ bruto daquele dia ([Shift.fixedShare]; o legado [Shift.fixedPerRide] sai), e
     * a soma do mês fica igual ao [total] (ou à parte dele, no mês em que o rateio começou). Sem corrida
     * no mês, nada muda.
     */
    fun settle(history: List<Shift>, total: Double, month: YearMonth, zone: ZoneId, since: LocalDate? = null): List<Shift> {
        val target = monthTarget(total, month, since)
        val first = maxOf(month.atDay(1), since ?: month.atDay(1))
        val last = month.atEndOfMonth()
        val inMonth = history.filter { val d = dateOf(it.startedAt, zone); d >= first && d <= last }
        val lastDay = inMonth.filter { it.finishedGross > 0 }.maxOfOrNull { dateOf(it.startedAt, zone) } ?: return history
        val paid = inMonth.filter { dateOf(it.startedAt, zone) < lastDay }.sumOf { charged(it) }
        val gross = inMonth.filter { dateOf(it.startedAt, zone) == lastDay }.sumOf { it.finishedGross }
        val share = max(0.0, target - paid) / gross
        return history.map {
            if (dateOf(it.startedAt, zone) == lastDay) it.copy(fixedShare = share, fixedPerRide = null) else it
        }
    }

    private fun finished(s: Shift): Int = s.trips.count { it.finished }

    /**
     * R$ de custo fixo que a jornada encerrada descontou, pela regra do [ShiftMath.summary]: % gravada ×
     * bruto; legado R$ por corrida × corridas; sem nenhum dos dois (antes dos custos fixos) = 0.
     */
    private fun charged(s: Shift): Double = ShiftMath.fixedCostOf(s, fixedShare = 0.0)

    private fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
