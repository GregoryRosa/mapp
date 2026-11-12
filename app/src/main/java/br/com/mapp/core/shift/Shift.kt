package br.com.mapp.core.shift

import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/*
 * Monitor de jornada (Kotlin puro): o motorista dá "play" no ícone "M", o MApp soma os km pelo GPS e
 * as corridas que ele marca como pegas. Persistência e GPS ficam em overlay/ShiftTracker.
 */

/** Corrida pega durante a jornada (valores da oferta lida na tela). */
data class ShiftTrip(
    val atMillis: Long,
    val platform: Platform,
    val category: String?,
    val price: Double,
    val pickupKm: Double?,
    val tripKm: Double?,
) {
    companion object {
        fun from(offer: RawOffer, atMillis: Long) = ShiftTrip(
            atMillis = atMillis,
            platform = offer.platform,
            category = offer.category,
            price = offer.price,
            pickupKm = offer.pickupKm,
            tripKm = offer.tripKm,
        )
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
) {
    val paused: Boolean get() = resumedAt == null

    fun elapsedMs(now: Long): Long = activeMs + (resumedAt?.let { max(0, now - it) } ?: 0)

    fun pause(now: Long): Shift = if (paused) this else copy(activeMs = elapsedMs(now), resumedAt = null)

    fun resume(now: Long): Shift = if (paused) copy(resumedAt = now) else this

    fun addKm(km: Double): Shift = if (km > 0) copy(gpsKm = gpsKm + km) else this

    fun addTrip(trip: ShiftTrip): Shift = copy(trips = trips + trip)

    fun undoLastTrip(): Shift = copy(trips = trips.dropLast(1))

    /**
     * Posição da corrida desta oferta na jornada: mesma plataforma e valor, contada perto do horário
     * em que a oferta apareceu ([offerAt]). -1 = não está.
     */
    fun tripIndexFor(platform: Platform, price: Double, offerAt: Long): Int = trips.indexOfFirst {
        it.platform == platform && abs(it.price - price) < 0.005 &&
            it.atMillis in (offerAt - MATCH_BEFORE_MS)..(offerAt + MATCH_AFTER_MS)
    }

    /** Marcação manual no histórico: coloca a corrida (se ainda não está) ou tira. */
    fun withTrip(trip: ShiftTrip, offerAt: Long, present: Boolean): Shift {
        val i = tripIndexFor(trip.platform, trip.price, offerAt)
        return when {
            present && i < 0 -> copy(trips = (trips + trip).sortedBy { it.atMillis })
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
)

object ShiftMath {
    const val NO_CATEGORY = "Outros"

    /** [costPerKm] vale só se a jornada não guardou o seu ([Shift.costPerKm], gravado ao encerrar). */
    fun summary(shift: Shift, costPerKm: Double, now: Long): ShiftSummary {
        val costPerKm = shift.costPerKm ?: costPerKm
        val trips = shift.trips
        val revenue = trips.sumOf { it.price }
        val withTrip = trips.sumOf { it.tripKm ?: 0.0 }
        val offersKm = trips.sumOf { (it.pickupKm ?: 0.0) + (it.tripKm ?: 0.0) }
        val total = max(shift.gpsKm, offersKm)
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
        )
    }

    /** % de corridas por tipo (UberX, Comfort, Black, Elétrico...), da mais comum para a menos. */
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
     * Jornada "do dia" mostrada junto ao ícone "M": a atual (mesmo que tenha começado ontem) ou,
     * sem jornada aberta, a última encerrada se ela começou hoje.
     */
    /**
     * Jornada em que uma oferta de [at] aconteceu: a atual (se começou antes) ou a encerrada mais
     * recente que começou antes dela ([history] da mais nova para a mais antiga). null = nenhuma.
     */
    fun ownerOf(at: Long, current: Shift?, history: List<Shift>): Shift? =
        current?.takeIf { at >= it.startedAt } ?: history.firstOrNull { at >= it.startedAt }

    fun ofToday(current: Shift?, last: Shift?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Shift? =
        current ?: last?.takeIf { sameDay(it.startedAt, now, zone) }

    fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** Linhas de texto do resumo (ícone "M" e tela inicial). */
    fun lines(s: ShiftSummary): List<String> = buildList {
        add("⏱ ${duration(s.hours)} · ${s.trips} corrida${if (s.trips == 1) "" else "s"}")
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
        append(listOf("v1", s.startedAt, s.activeMs, s.resumedAt ?: "", s.gpsKm, s.costPerKm ?: "").joinToString(SEP))
        s.trips.forEach { t ->
            append('\n')
            append(
                listOf(t.atMillis, t.platform.name, clean(t.category), t.price, t.pickupKm ?: "", t.tripKm ?: "")
                    .joinToString(SEP),
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
                trips = rows.drop(1).filter { it.isNotBlank() }.map { row ->
                    val c = row.split(SEP)
                    ShiftTrip(
                        atMillis = c[0].toLong(),
                        platform = Platform.valueOf(c[1]),
                        category = c[2].ifEmpty { null },
                        price = c[3].toDouble(),
                        pickupKm = c[4].toDoubleOrNull(),
                        tripKm = c[5].toDoubleOrNull(),
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
