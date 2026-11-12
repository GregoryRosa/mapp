package br.com.mapp.finance

import br.com.mapp.core.model.Signal
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Intervalo de tempo inclusivo [from, to] em epoch millis (mesma semântica dos repositórios). */
data class Period(val from: Long, val to: Long) {
    operator fun contains(millis: Long): Boolean = millis in from..to
}

data class PeriodSummary(
    val income: Double,
    val expense: Double,
    val km: Double,
    val incomeCount: Int,
    val expenseCount: Int,
    /** Soma dos ganhos que tinham km informado (base do R$/km). */
    val incomeWithKm: Double = 0.0,
) {
    val profit: Double get() = income - expense
    /** R$/km médio: ganhos com km informado / km rodados. */
    val incomePerKm: Double? get() = if (km > 0) incomeWithKm / km else null
}

data class HourBucket(val startHour: Int, val endHour: Int, val total: Double)

data class HourStat(val hour: Int, val avgPerKm: Double, val offers: Int)

/** Corridas aceitas num período: quantidade, soma dos valores e do lucro líquido, médias. */
data class AcceptedSummary(
    val count: Int,
    val total: Double,
    /** Soma do lucro líquido das corridas que têm lucro calculado; null se nenhuma tem. */
    val profit: Double?,
    val avgPerKm: Double?,
    val avgPerHour: Double?,
)

data class OfferSummary(
    val count: Int,
    val avgPerKm: Double?,
    val avgPerHour: Double?,
    val signals: Map<Signal, Int>,
)

data class GoalProgress(val goal: Double, val achieved: Double) {
    /** Fração 0..1 para barras de progresso. */
    val fraction: Double get() = (achieved / goal).coerceIn(0.0, 1.0)
    val percent: Int get() = (achieved / goal * 100).toInt()
    val remaining: Double get() = (goal - achieved).coerceAtLeast(0.0)
    val reached: Boolean get() = achieved >= goal
}

/** Cálculos de relatório puros (sem Android). Fuso padrão: o do aparelho. */
object ReportMath {

    // ---------- Períodos ----------

    fun today(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Period =
        lastDays(1, now, zone)

    /** Últimos [days] dias corridos, incluindo hoje (de 00:00 de hoje-(days-1) até o fim de hoje). */
    fun lastDays(days: Int, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Period {
        require(days >= 1) { "days deve ser >= 1" }
        val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = todayDate.minusDays((days - 1).toLong()).atStartOfDay(zone)
        val end = todayDate.plusDays(1).atStartOfDay(zone)
        return Period(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli() - 1)
    }

    /** Mês corrente inteiro (dia 1 00:00 até o último instante do mês). */
    fun currentMonth(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Period {
        val first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        val start = first.atStartOfDay(zone)
        val end = first.plusMonths(1).atStartOfDay(zone)
        return Period(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli() - 1)
    }

    // ---------- Lançamentos ----------

    fun summarize(entries: List<FinanceEntry>): PeriodSummary {
        var income = 0.0
        var expense = 0.0
        var km = 0.0
        var incomeWithKm = 0.0
        var nIncome = 0
        var nExpense = 0
        for (e in entries) {
            when (e.kind) {
                EntryKind.INCOME -> {
                    income += e.amount
                    nIncome++
                    val k = e.km
                    if (k != null && k > 0) {
                        km += k
                        incomeWithKm += e.amount
                    }
                }
                EntryKind.EXPENSE -> {
                    expense += e.amount
                    nExpense++
                }
            }
        }
        return PeriodSummary(income, expense, km, nIncome, nExpense, incomeWithKm)
    }

    fun summarize(entries: List<FinanceEntry>, period: Period): PeriodSummary =
        summarize(entries.filter { it.atMillis in period })

    /** Ganhos por dia da semana. Índice 0 = segunda ... 6 = domingo. */
    fun incomeByWeekday(entries: List<FinanceEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Double> {
        val totals = DoubleArray(7)
        entries.filter { it.kind == EntryKind.INCOME }.forEach {
            totals[at(it.atMillis, zone).dayOfWeek.value - 1] += it.amount
        }
        return totals.toList()
    }

    /** Ganhos por faixa horária de [bucketHours] horas (deve dividir 24). */
    fun incomeByHour(
        entries: List<FinanceEntry>,
        bucketHours: Int = 1,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HourBucket> {
        require(bucketHours in 1..24 && 24 % bucketHours == 0) { "bucketHours deve dividir 24" }
        val totals = DoubleArray(24 / bucketHours)
        entries.filter { it.kind == EntryKind.INCOME }.forEach {
            totals[at(it.atMillis, zone).hour / bucketHours] += it.amount
        }
        return totals.mapIndexed { i, total -> HourBucket(i * bucketHours, (i + 1) * bucketHours, total) }
    }

    // ---------- Ofertas ----------

    /**
     * Melhores horas do dia por R$/km médio das ofertas vistas.
     * Média ponderada: soma dos valores / soma dos km (ofertas sem km são ignoradas).
     * Desempate pelo número de ofertas.
     */
    fun bestHours(
        offers: List<OfferRecord>,
        top: Int = 5,
        minOffers: Int = 1,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HourStat> {
        val price = DoubleArray(24)
        val km = DoubleArray(24)
        val count = IntArray(24)
        offers.forEach { r ->
            val k = r.evaluated.metrics.totalKm ?: return@forEach
            if (k <= 0) return@forEach
            val h = at(r.evaluated.raw.capturedAt, zone).hour
            price[h] += r.evaluated.raw.price
            km[h] += k
            count[h]++
        }
        return (0 until 24)
            .filter { count[it] >= minOffers && count[it] > 0 }
            .map { HourStat(it, price[it] / km[it], count[it]) }
            .sortedWith(compareByDescending<HourStat> { it.avgPerKm }.thenByDescending { it.offers })
            .take(top)
    }

    fun signalCounts(offers: List<OfferRecord>): Map<Signal, Int> {
        val map = Signal.entries.associateWith { 0 }.toMutableMap()
        offers.forEach { map[it.evaluated.signal] = map.getValue(it.evaluated.signal) + 1 }
        return map
    }

    /** R$/km e R$/h médios ponderados das ofertas (soma valores / soma km ou horas). */
    fun summarizeOffers(offers: List<OfferRecord>): OfferSummary {
        var priceKm = 0.0
        var km = 0.0
        var priceMin = 0.0
        var min = 0.0
        offers.forEach { r ->
            val m = r.evaluated.metrics
            val p = r.evaluated.raw.price
            m.totalKm?.takeIf { it > 0 }?.let { km += it; priceKm += p }
            m.totalMin?.takeIf { it > 0 }?.let { min += it; priceMin += p }
        }
        return OfferSummary(
            count = offers.size,
            avgPerKm = if (km > 0) priceKm / km else null,
            avgPerHour = if (min > 0) priceMin / min * 60.0 else null,
            signals = signalCounts(offers),
        )
    }

    /** Só as ofertas marcadas como aceitas. */
    fun accepted(offers: List<OfferRecord>): List<OfferRecord> = offers.filter { it.accepted == true }

    /** Resumo das corridas aceitas (ignora as não aceitas da lista). */
    fun summarizeAccepted(offers: List<OfferRecord>): AcceptedSummary {
        val list = accepted(offers)
        val profits = list.mapNotNull { it.evaluated.metrics.netProfit }
        val avg = summarizeOffers(list)
        return AcceptedSummary(
            count = list.size,
            total = list.sumOf { it.evaluated.raw.price },
            profit = if (profits.isEmpty()) null else profits.sum(),
            avgPerKm = avg.avgPerKm,
            avgPerHour = avg.avgPerHour,
        )
    }

    // ---------- Metas ----------

    /** Progresso de uma meta de ganhos. null quando não há meta (null ou <= 0). */
    fun goalProgress(achieved: Double, goal: Double?): GoalProgress? =
        goal?.takeIf { it > 0 }?.let { GoalProgress(it, achieved) }

    private fun at(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
}
