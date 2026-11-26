package br.com.mapp.core.shift

import br.com.mapp.core.model.KmBase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Média de km das jornadas encerradas nos últimos [KmAverages.WINDOW_DAYS] dias.
 * [kmPerDay] = média por dia rodado (dia com jornada), [grossPerDay] = R$ bruto das corridas
 * finalizadas por dia rodado, arredondado para R$ inteiro (null = nenhuma corrida contada na janela),
 * [workedDays] = dias rodados na janela,
 * [windowDays] = do 1º dia rodado da janela até hoje (1..30), [daysPerMonth] = dias rodados por mês
 * pelo ritmo da janela (null com menos de [KmAverages.MIN_WINDOW_FOR_DAYS] dias de histórico:
 * pouco para saber as folgas).
 */
data class KmAverage(
    val kmPerDay: Double,
    val workedDays: Int,
    val windowDays: Int,
    val daysPerMonth: Double?,
    val grossPerDay: Double? = null,
)

/** Base dos custos do mês (Financeiro → Custos fixos) pela média das jornadas. */
object KmAverages {
    const val WINDOW_DAYS = 30
    const val MIN_WINDOW_FOR_DAYS = 14
    /** Dia com menos que isso (GPS parado, jornada aberta e fechada) não conta como dia rodado. */
    const val MIN_DAY_KM = 1.0

    /** null = nenhuma jornada com km na janela. Jornada que vira a meia-noite conta no dia em que começou. */
    fun of(shifts: List<Shift>, now: Long, zone: ZoneId): KmAverage? {
        val today = dayOf(now, zone)
        val first = today.minusDays(WINDOW_DAYS - 1L)
        val days = shifts
            .groupBy { dayOf(it.startedAt, zone) }
            .filterKeys { !it.isBefore(first) && !it.isAfter(today) }
            .filterValues { list -> list.sumOf { it.gpsKm } >= MIN_DAY_KM }
        val byDay = days.mapValues { (_, list) -> list.sumOf { it.gpsKm } }
        if (byDay.isEmpty()) return null
        val worked = byDay.size
        val kmPerDay = (byDay.values.sum() / worked).roundToInt().coerceAtLeast(1).toDouble()
        val gross = days.values.sumOf { list -> list.sumOf { it.finishedGross } }
        val grossPerDay = if (gross > 0) (gross / worked).roundToInt().coerceAtLeast(1).toDouble() else null
        val window = (ChronoUnit.DAYS.between(byDay.keys.min(), today) + 1).toInt().coerceIn(1, WINDOW_DAYS)
        val perMonth = if (window >= MIN_WINDOW_FOR_DAYS) {
            (worked * WINDOW_DAYS.toDouble() / window).roundToInt().coerceIn(1, WINDOW_DAYS).toDouble()
        } else {
            null
        }
        return KmAverage(kmPerDay, worked, window, perMonth, grossPerDay)
    }

    /**
     * Base com a média aplicada: só com [KmBase.auto]. Sem média, com pouco histórico para os dias
     * do mês ou sem corridas contadas (faturamento), fica o valor que já estava (o padrão ou o último
     * digitado).
     */
    fun applied(base: KmBase, avg: KmAverage?): KmBase {
        if (!base.auto || avg == null) return base
        return base.copy(
            kmPerDay = avg.kmPerDay,
            daysPerMonth = avg.daysPerMonth ?: base.daysPerMonth,
            grossPerDay = avg.grossPerDay ?: base.grossPerDay,
        )
    }

    private fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
