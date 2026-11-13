package br.com.mapp.core.shift

import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/*
 * Monitor de jornada (Kotlin puro): o motorista dá "play" no ícone "M", o MApp soma os km pelo GPS e
 * as corridas que ele marca como pegas. Persistência e GPS ficam em overlay/ShiftTracker.
 */

/**
 * Corrida pega durante a jornada (valores da oferta lida na tela). [finished] = false: aceita e ainda
 * em andamento — não entra no ganho/lucro até ser finalizada (se for cancelada, nunca entra).
 */
data class ShiftTrip(
    val atMillis: Long,
    val platform: Platform,
    val category: String?,
    val price: Double,
    val pickupKm: Double?,
    val tripKm: Double?,
    val finished: Boolean = true,
) {
    companion object {
        fun from(offer: RawOffer, atMillis: Long, finished: Boolean = true) = ShiftTrip(
            atMillis = atMillis,
            platform = offer.platform,
            category = offer.category,
            price = offer.price,
            pickupKm = offer.pickupKm,
            tripKm = offer.tripKm,
            finished = finished,
        )

        /** Nome do tipo gravado por versão antiga: "Elétrico" agora é "Electric", como na Uber. */
        fun categoryName(saved: String?): String? = when (saved) {
            null, "" -> null
            "Elétrico" -> "Electric"
            else -> saved
        }
    }
}

/**
 * Jornada em andamento. [resumedAt] = null → pausada. [activeMs] = tempo trabalhado antes do último
 * play; o tempo corrente é somado em [elapsedMs].
 */
data class Shift(
    val startedAt: Long,
    val activeMs: Long = 0,
    val resumedAt: Long? = startedAt,
    val gpsKm: Double = 0.0,
    val trips: List<ShiftTrip> = emptyList(),
    /** Custo/km do veículo no momento em que a jornada foi encerrada (null = usar o atual). */
    val costPerKm: Double? = null,
    /** Quando foi encerrada (null = aberta, ou gravada por versão antiga). */
    val endedAt: Long? = null,
) {
    val paused: Boolean get() = resumedAt == null

    fun elapsedMs(now: Long): Long = activeMs + (resumedAt?.let { max(0, now - it) } ?: 0)

    fun pause(now: Long): Shift = if (paused) this else copy(activeMs = elapsedMs(now), resumedAt = null)

    fun resume(now: Long): Shift = if (paused) copy(resumedAt = now) else this

    fun addKm(km: Double): Shift = if (km > 0) copy(gpsKm = gpsKm + km) else this

    fun addTrip(trip: ShiftTrip): Shift = copy(trips = trips + trip)

    fun undoLastTrip(): Shift = copy(trips = trips.dropLast(1))

    /** Corrida aceita mais recente que ainda não terminou (null = nenhuma). */
    val pendingTrip: ShiftTrip? get() = trips.lastOrNull { !it.finished }

    private val pendingIndex: Int get() = trips.indexOfLast { !it.finished }

    /**
     * Próxima a terminar: a aceita mais ANTIGA ainda aberta. Com corrida aceita durante outra viagem
     * há duas abertas, e a que termina primeiro é a que já estava em andamento.
     */
    val nextToFinish: ShiftTrip? get() = trips.firstOrNull { !it.finished }

    /**
     * Corrida em andamento terminou ([nextToFinish]): passa a contar no ganho/lucro. Com [platform],
     * a mais antiga aberta daquele app (Uber e 99 ligados juntos).
     */
    fun finishPending(platform: Platform? = null): Shift {
        val i = trips.indexOfFirst { !it.finished && (platform == null || it.platform == platform) }
        if (i < 0) return this
        return copy(trips = trips.mapIndexed { j, t -> if (j == i) t.copy(finished = true) else t })
    }

    /** Corrida em andamento foi cancelada: sai da jornada (a mais recente; com [platform], daquele app). */
    fun cancelPending(platform: Platform? = null): Shift {
        val i = if (platform == null) pendingIndex else trips.indexOfLast { !it.finished && it.platform == platform }
        if (i < 0) return this
        return copy(trips = trips.filterIndexed { j, _ -> j != i })
    }

    /** Corrida aberta mais antiga de [platform] (null = de qualquer app). */
    fun nextToFinish(platform: Platform?): ShiftTrip? =
        trips.firstOrNull { !it.finished && (platform == null || it.platform == platform) }

    /** Corrida aberta mais recente de [platform] (null = de qualquer app). */
    fun pendingTrip(platform: Platform?): ShiftTrip? =
        trips.lastOrNull { !it.finished && (platform == null || it.platform == platform) }

    /**
     * Posição da corrida desta oferta na jornada: mesma plataforma e valor, contada perto do horário
     * em que a oferta apareceu ([offerAt]). -1 = não está.
     */
    fun tripIndexFor(platform: Platform, price: Double, offerAt: Long): Int = trips.indexOfFirst {
        it.platform == platform && abs(it.price - price) < 0.005 &&
            it.atMillis in (offerAt - MATCH_BEFORE_MS)..(offerAt + MATCH_AFTER_MS)
    }

    /**
     * Marcação manual no histórico: coloca a corrida (se ainda não está) ou tira. Marcar como aceita
     * depois do fato = corrida feita, então ela entra (ou fica) como finalizada.
     */
    fun withTrip(trip: ShiftTrip, offerAt: Long, present: Boolean): Shift {
        val i = tripIndexFor(trip.platform, trip.price, offerAt)
        return when {
            present && i < 0 -> copy(trips = (trips + trip.copy(finished = true)).sortedBy { it.atMillis })
            present && !trips[i].finished -> copy(trips = trips.mapIndexed { j, t -> if (j == i) t.copy(finished = true) else t })
            !present && i >= 0 -> copy(trips = trips.filterIndexed { j, _ -> j != i })
            else -> this
        }
    }

    companion object {
        /** Corrida contada até 1 min antes / 20 min depois da oferta é a mesma corrida. */
        const val MATCH_BEFORE_MS = 60_000L
        const val MATCH_AFTER_MS = 20 * 60_000L
    }
}

data class CategoryShare(val label: String, val count: Int, val percent: Double, val revenue: Double)

data class ShiftSummary(
    val hours: Double,
    val trips: Int,
    val revenue: Double,
    /** Km rodados no total: GPS (ou a soma das corridas, se o GPS somou menos). */
    val totalKm: Double,
    /** Km com passageiro (trecho da viagem das corridas pegas). */
    val kmWithTrip: Double,
    /** Km sem passageiro (busca + rodando vazio). */
    val kmWithoutTrip: Double,
    val cost: Double,
    val profit: Double,
    val perKm: Double?,
    val perHour: Double?,
    val avgTripKm: Double?,
    val categories: List<CategoryShare>,
    /** Corridas aceitas ainda em andamento (fora do ganho/lucro até finalizar). */
    val pendingTrips: Int = 0,
)

object ShiftMath {
    const val NO_CATEGORY = "Outros"

    /**
     * [costPerKm] vale só se a jornada não guardou o seu ([Shift.costPerKm], gravado ao encerrar).
     * Ganho/lucro: só corridas finalizadas. Custo: km do GPS × custo/km (sobe a cada trecho rodado,
     * não salta quando uma corrida é aceita); sem GPS, usa os km das corridas finalizadas.
     */
    fun summary(shift: Shift, costPerKm: Double, now: Long): ShiftSummary {
        val costPerKm = shift.costPerKm ?: costPerKm
        val trips = shift.trips.filter { it.finished }
        val revenue = trips.sumOf { it.price }
        val withTrip = trips.sumOf { it.tripKm ?: 0.0 }
        val offersKm = trips.sumOf { (it.pickupKm ?: 0.0) + (it.tripKm ?: 0.0) }
        val total = if (shift.gpsKm > 0) shift.gpsKm else offersKm
        val hours = shift.elapsedMs(now) / 3_600_000.0
        val cost = total * costPerKm
        val withKm = trips.mapNotNull { it.tripKm }
        return ShiftSummary(
            hours = hours,
            trips = trips.size,
            revenue = revenue,
            totalKm = total,
            kmWithTrip = withTrip,
            kmWithoutTrip = max(0.0, total - withTrip),
            cost = cost,
            profit = revenue - cost,
            perKm = if (total >= 0.1 && trips.isNotEmpty()) revenue / total else null,
            perHour = if (hours >= 1.0 / 60 && trips.isNotEmpty()) revenue / hours else null,
            avgTripKm = if (withKm.isNotEmpty()) withKm.average() else null,
            categories = categories(trips),
            pendingTrips = shift.trips.size - trips.size,
        )
    }

    /** % de corridas por tipo (UberX, Comfort, Black, Electric...), da mais comum para a menos. */
    fun categories(trips: List<ShiftTrip>): List<CategoryShare> {
        if (trips.isEmpty()) return emptyList()
        return trips.groupBy { it.category ?: NO_CATEGORY }
            .map { (label, list) ->
                CategoryShare(label, list.size, list.size * 100.0 / trips.size, list.sumOf { it.price })
            }
            .sortedWith(compareByDescending<CategoryShare> { it.count }.thenByDescending { it.revenue })
    }

    /** Soma de várias jornadas (histórico: semana, mês). */
    fun combine(list: List<ShiftSummary>): ShiftSummary {
        val revenue = list.sumOf { it.revenue }
        val total = list.sumOf { it.totalKm }
        val hours = list.sumOf { it.hours }
        val trips = list.sumOf { it.trips }
        val withTrip = list.sumOf { it.kmWithTrip }
        val cost = list.sumOf { it.cost }
        val tripsWithKm = list.sumOf { s -> if (s.avgTripKm != null && s.avgTripKm > 0) s.kmWithTrip / s.avgTripKm else 0.0 }
        val cats = list.flatMap { it.categories }.groupBy { it.label }
            .map { (label, c) -> CategoryShare(label, c.sumOf { it.count }, 0.0, c.sumOf { it.revenue }) }
            .map { it.copy(percent = if (trips > 0) it.count * 100.0 / trips else 0.0) }
            .sortedWith(compareByDescending<CategoryShare> { it.count }.thenByDescending { it.revenue })
        return ShiftSummary(
            hours = hours,
            trips = trips,
            revenue = revenue,
            totalKm = total,
            kmWithTrip = withTrip,
            kmWithoutTrip = max(0.0, total - withTrip),
            cost = cost,
            profit = revenue - cost,
            perKm = if (total >= 0.1 && trips > 0) revenue / total else null,
            perHour = if (hours >= 1.0 / 60 && trips > 0) revenue / hours else null,
            avgTripKm = if (tripsWithKm >= 0.5) withTrip / tripsWithKm else null,
            categories = cats,
        )
    }

    /**
     * Jornada em que uma oferta de [at] aconteceu: a atual (se começou antes) ou a encerrada mais
     * recente que começou antes dela ([history] da mais nova para a mais antiga). null = nenhuma.
     */
    fun ownerOf(at: Long, current: Shift?, history: List<Shift>): Shift? =
        current?.takeIf { at >= it.startedAt } ?: history.firstOrNull { at >= it.startedAt }

    /**
     * Jornada "do dia" mostrada junto ao ícone "M": a atual (mesmo que tenha começado ontem) ou,
     * sem jornada aberta, a última encerrada se ela começou hoje.
     */
    fun ofToday(current: Shift?, last: Shift?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Shift? =
        current ?: last?.takeIf { sameDay(it.startedAt, now, zone) }

    /**
     * Custos do veículo mudaram: as jornadas que começaram hoje (já encerradas, com o custo/km
     * guardado) passam a usar [costPerKm]. Dias anteriores ficam com o custo/km daquele dia.
     */
    fun withCostToday(list: List<Shift>, costPerKm: Double, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Shift> =
        list.map { if (it.costPerKm != null && sameDay(it.startedAt, now, zone)) it.copy(costPerKm = costPerKm) else it }

    fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** Linhas de texto do resumo (ícone "M" e tela inicial). */
    fun lines(s: ShiftSummary): List<String> = buildList {
        add("⏱ ${duration(s.hours)} · ${s.trips} corrida${if (s.trips == 1) "" else "s"}")
        if (s.pendingTrips > 0) add("🚕 ${s.pendingTrips} em andamento · soma no lucro ao finalizar")
        add("💰 Ganho ${money(s.revenue)} · Custo ${money(s.cost)}")
        add("${if (s.profit >= 0) "✅" else "🔻"} Lucro ${money(s.profit)}")
        add("🚗 ${km(s.totalKm)} rodados")
        add("    com passageiro ${km(s.kmWithTrip)} · sem ${km(s.kmWithoutTrip)}")
        add("R$/km ${s.perKm?.let { num(it, 2) } ?: "—"} · R$/h ${s.perHour?.let { num(it, 2) } ?: "—"}")
        add("Média por corrida ${s.avgTripKm?.let { km(it) } ?: "—"}")
        if (s.categories.isNotEmpty()) {
            add(s.categories.joinToString(" · ") { "${it.label} ${num(it.percent, 0)}%" })
        }
    }

    fun duration(hours: Double): String {
        val min = (hours * 60).toLong()
        return "${min / 60}h" + String.format(Locale.ROOT, "%02dm", min % 60)
    }

    fun money(v: Double): String = "R$ " + num(v, 2)
    fun km(v: Double): String = num(v, 1) + " km"
    fun num(v: Double, decimals: Int): String = String.format(PT_BR, "%.${decimals}f", v)

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
}

/**
 * Totais de um período (dia, semana da Uber, mês). É a MESMA conta em todas as telas — tela inicial,
 * Aceitas hoje, Ganhos e gastos e Relatórios —, então os números batem entre elas.
 */
data class PeriodTotals(
    /** Corridas finalizadas nas jornadas + corridas aceitas fora de jornada. */
    val rides: Int = 0,
    val ridesGross: Double = 0.0,
    /** Ganhos lançados à mão em Ganhos e gastos. */
    val manualIncome: Double = 0.0,
    /** Corridas aceitas ainda em andamento (entram no ganho ao finalizar). */
    val pendingRides: Int = 0,
    /** Km rodados: GPS das jornadas (com e sem passageiro) + km das corridas fora de jornada. */
    val km: Double = 0.0,
    val kmWithTrip: Double = 0.0,
    /** Tempo trabalhado: jornadas (sem as pausas) + tempo das corridas fora de jornada. */
    val hours: Double = 0.0,
    /** Custo do carro: km rodados × custo/km do veículo. */
    val carCost: Double = 0.0,
    /** Gastos lançados à mão. */
    val expenses: Double = 0.0,
    val avgTripKm: Double? = null,
    val categories: List<CategoryShare> = emptyList(),
    val shifts: Int = 0,
) {
    /** Valor bruto: tudo o que entrou, sem tirar custos. */
    val gross: Double get() = ridesGross + manualIncome
    val cost: Double get() = carCost + expenses
    /** Valor líquido: bruto − custo do carro − gastos lançados. */
    val net: Double get() = gross - cost
    val kmWithoutTrip: Double get() = max(0.0, km - kmWithTrip)
    private val hasKm: Boolean get() = km >= 0.1 && gross > 0
    private val hasTime: Boolean get() = hours >= 1.0 / 60 && gross > 0
    val perKm: Double? get() = if (hasKm) gross / km else null
    val netPerKm: Double? get() = if (hasKm) net / km else null
    val perHour: Double? get() = if (hasTime) gross / hours else null
    val netPerHour: Double? get() = if (hasTime) net / hours else null
    /** Custo/km efetivo do período (jornadas encerradas guardam o custo/km do dia). */
    val carCostPerKm: Double? get() = if (km > 0) carCost / km else null
    val isEmpty: Boolean
        get() = rides == 0 && pendingRides == 0 && shifts == 0 && manualIncome <= 0.0 && expenses <= 0.0
}

object PeriodMath {
    /**
     * Soma o período [from]..[to] sem contar nada duas vezes:
     * - jornadas que COMEÇARAM no período: corridas finalizadas, km do GPS, tempo e custo
     *   ([ShiftMath.summary]). Dentro do horário de uma jornada, ela manda: corrida cancelada lá não
     *   conta, mesmo que tenha ficado marcada como aceita no histórico de ofertas;
     * - corridas marcadas como aceitas no histórico FORA de qualquer jornada: valor, km (busca + viagem)
     *   e tempo da oferta, custo = km × [costPerKm];
     * - lançamentos manuais: ganho soma no bruto (km informado soma nos km e no custo do carro), gasto
     *   soma nos custos.
     * [current] = jornada aberta (vale até [now]); [history] = encerradas.
     */
    fun totals(
        from: Long,
        to: Long,
        current: Shift?,
        history: List<Shift>,
        offers: List<OfferRecord>,
        entries: List<FinanceEntry>,
        costPerKm: Double,
        now: Long,
    ): PeriodTotals {
        val spans = listOfNotNull(current).map { it to max(now, it.startedAt) } +
            history.map { it to (it.endedAt ?: (it.startedAt + it.activeMs)) }
        val inPeriod = spans.map { it.first }.filter { it.startedAt in from..to }
        val summaries = inPeriod.map { ShiftMath.summary(it, costPerKm, now) }

        val loose = offers.filter { r ->
            val raw = r.evaluated.raw
            r.accepted == true && raw.capturedAt in from..to &&
                spans.none { (s, end) ->
                    raw.capturedAt in s.startedAt..end || s.tripIndexFor(raw.platform, raw.price, raw.capturedAt) >= 0
                }
        }
        val looseKm = loose.sumOf { offerKm(it) }
        val looseHours = loose.sumOf { it.evaluated.metrics.totalMin ?: 0 } / 60.0

        val inRange = entries.filter { it.atMillis in from..to }
        val income = inRange.filter { it.kind == EntryKind.INCOME }
        val manualKm = income.sumOf { e -> e.km?.takeIf { it > 0 } ?: 0.0 }

        val rides = inPeriod.flatMap { s -> s.trips.filter { it.finished } } +
            loose.map { ShiftTrip.from(it.evaluated.raw, it.evaluated.raw.capturedAt) }
        val tripKms = rides.mapNotNull { it.tripKm }
        return PeriodTotals(
            rides = rides.size,
            ridesGross = rides.sumOf { it.price },
            manualIncome = income.sumOf { it.amount },
            pendingRides = inPeriod.sumOf { s -> s.trips.count { !it.finished } },
            km = summaries.sumOf { it.totalKm } + looseKm + manualKm,
            kmWithTrip = tripKms.sum(),
            hours = summaries.sumOf { it.hours } + looseHours,
            carCost = summaries.sumOf { it.cost } + (looseKm + manualKm) * costPerKm,
            expenses = inRange.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.amount },
            avgTripKm = if (tripKms.isNotEmpty()) tripKms.average() else null,
            categories = ShiftMath.categories(rides),
            shifts = inPeriod.size,
        )
    }

    /** Km da oferta: busca + viagem (com o que foi estimado quando o OCR não leu). */
    private fun offerKm(r: OfferRecord): Double {
        val raw = r.evaluated.raw
        return r.evaluated.metrics.totalKm ?: ((raw.pickupKm ?: 0.0) + (raw.tripKm ?: 0.0))
    }
}

/** Posição do GPS com precisão (metros) e horário. */
data class Fix(val point: GeoPoint, val accuracyM: Float?, val atMillis: Long)

/**
 * Hodômetro pelo GPS: ignora leituras imprecisas, o "tremido" parado no lugar e saltos impossíveis.
 * Movimentos pequenos acumulam (a âncora só anda quando o deslocamento passa do mínimo).
 */
class Odometer(
    private val maxAccuracyM: Float = 50f,
    private val minStepM: Double = 25.0,
    private val maxSpeedKmh: Double = 160.0,
) {
    private var anchor: Fix? = null

    /** Km a somar com esta leitura (0 se descartada). */
    fun add(fix: Fix): Double {
        val acc = fix.accuracyM
        if (acc != null && acc > maxAccuracyM) return 0.0
        val a = anchor ?: run { anchor = fix; return 0.0 }
        if (fix.atMillis <= a.atMillis) return 0.0
        val km = Geo.haversineKm(a.point, fix.point)
        val hours = (fix.atMillis - a.atMillis) / 3_600_000.0
        if (km / hours > maxSpeedKmh) { anchor = fix; return 0.0 } // salto de GPS: recomeça daqui
        if (km * 1000 < max(minStepM, (acc ?: 0f).toDouble())) return 0.0
        anchor = fix
        return km
    }

    /** Pausa/retomada: o caminho feito com a jornada parada não conta. */
    fun reset() { anchor = null }
}

/** Grava a jornada em texto (SharedPreferences). Formato próprio, linha a linha, sem bibliotecas. */
object ShiftCodec {
    private const val SEP = "\t"

    fun encode(s: Shift): String = buildString {
        append(
            listOf("v1", s.startedAt, s.activeMs, s.resumedAt ?: "", s.gpsKm, s.costPerKm ?: "", s.endedAt ?: "")
                .joinToString(SEP),
        )
        s.trips.forEach { t ->
            append('\n')
            append(
                listOf(
                    t.atMillis, t.platform.name, clean(t.category), t.price, t.pickupKm ?: "", t.tripKm ?: "",
                    if (t.finished) "" else "0",
                ).joinToString(SEP),
            )
        }
    }

    fun decode(text: String?): Shift? {
        if (text.isNullOrBlank()) return null
        return try {
            val rows = text.split('\n')
            val h = rows.first().split(SEP)
            if (h[0] != "v1") return null
            Shift(
                startedAt = h[1].toLong(),
                activeMs = h[2].toLong(),
                resumedAt = h[3].toLongOrNull(),
                gpsKm = h[4].toDouble(),
                costPerKm = h.getOrNull(5)?.toDoubleOrNull(),
                endedAt = h.getOrNull(6)?.toLongOrNull(),
                trips = rows.drop(1).filter { it.isNotBlank() }.map { row ->
                    val c = row.split(SEP)
                    ShiftTrip(
                        atMillis = c[0].toLong(),
                        platform = Platform.valueOf(c[1]),
                        category = ShiftTrip.categoryName(c[2]),
                        price = c[3].toDouble(),
                        pickupKm = c[4].toDoubleOrNull(),
                        tripKm = c[5].toDoubleOrNull(),
                        finished = c.getOrNull(6) != "0", // gravações antigas: finalizada
                    )
                },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Separador entre jornadas da lista (caractere de controle que nunca aparece nos dados). */
    private const val RECORD = "\u001E"

    fun encodeList(list: List<Shift>): String = list.joinToString(RECORD) { encode(it) }

    /** Jornadas ilegíveis são puladas (não apagam as outras). */
    fun decodeList(text: String?): List<Shift> =
        if (text.isNullOrBlank()) emptyList() else text.split(RECORD).mapNotNull { decode(it) }

    private fun clean(v: String?) = v?.replace('\t', ' ')?.replace('\n', ' ') ?: ""
}
