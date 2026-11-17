package br.com.mapp.core.heat

import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import java.time.Instant
import java.time.ZoneId

/*
 * Mapa de calor das zonas (aba Zonas → Mapa de calor). Kotlin puro, testado em JVM.
 *
 * A cidade é dividida em quadradinhos iguais; cada oferta do histórico cai num quadradinho pela
 * coordenada da coleta (ou do destino) e o quadradinho ganha cor pela métrica escolhida. Quadradinhos
 * quentes e vizinhos viram uma zona automática, que o motorista pode salvar como zona de verdade.
 *
 * É o mapa do próprio motorista: mostra onde ELE recebeu chamadas, não a demanda da cidade.
 * Oferta cujo endereço o Geocoder não localizou não tem coordenada e fica de fora.
 */

/** O que o mapa pinta (um botão para cada). */
enum class HeatMetric(val label: String) {
    CALLS("Chamadas"),
    PER_KM("R\$/km"),
    PER_HOUR("R\$/h"),
    PROFIT("Lucro"),
    PRICE("Valor"),
    CATEGORY("Modalidade"),
    ;

    /** Métrica de dinheiro: o quadradinho tira a média e quem não tem o número não pinta. */
    val isMoney: Boolean get() = this == PER_KM || this == PER_HOUR || this == PROFIT || this == PRICE

    /** Quanto maior, mais quente (vale para todas: lucro negativo fica no frio). */
    val higherIsBetter: Boolean get() = true
}

/** Período do histórico olhado. [days] = 0 significa tudo que está gravado. */
enum class HeatPeriod(val label: String, val days: Int) {
    DAYS_7("7 dias", 7),
    DAYS_30("30 dias", 30),
    DAYS_90("90 dias", 90),
    ALL("Tudo", 0),
    ;

    /** Começo do período (inclusive) em millis. */
    fun from(now: Long): Long = if (days <= 0) 0L else now - days * DAY_MS

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Ponto da corrida que entra no mapa. */
enum class HeatSpot(val label: String) {
    PICKUP("Coleta"),
    DROPOFF("Destino"),
}

/** Quais corridas contam: todas as ofertas recebidas ou só as que você aceitou. */
enum class HeatSet(val label: String) {
    ALL("Todas"),
    ACCEPTED("Só aceitas"),
}

/**
 * Faixa de horário do filtro, em horas locais. [from] inclusive, [to] exclusive; vira a meia-noite
 * (de 22 a 4 = 22h, 23h, 0h, 1h, 2h, 3h). from == to = todas as horas.
 */
data class HeatHours(val from: Int, val to: Int) {
    val isAll: Boolean get() = from == to

    fun contains(hour: Int): Boolean = when {
        isAll -> true
        from < to -> hour >= from && hour < to
        else -> hour >= from || hour < to
    }

    val label: String get() = if (isAll) "Todo dia" else "${two(from)}h–${two(to)}h"

    private fun two(h: Int): String = if (h < 10) "0$h" else h.toString()
}

/** Filtros da aba (os botões em cima do mapa). Conjunto vazio = sem filtrar por isso. */
data class HeatFilter(
    val metric: HeatMetric = HeatMetric.CALLS,
    val period: HeatPeriod = HeatPeriod.DAYS_30,
    val hours: HeatHours? = null,
    val platforms: Set<Platform> = emptySet(),
    val categories: Set<String> = emptySet(),
    val spot: HeatSpot = HeatSpot.PICKUP,
    val set: HeatSet = HeatSet.ALL,
)

/** Uma oferta do histórico já reduzida ao que o mapa usa. */
data class HeatPoint(
    val point: GeoPoint,
    val price: Double,
    val perKm: Double? = null,
    val perHour: Double? = null,
    val profit: Double? = null,
    val platform: Platform = Platform.UBER,
    val category: String? = null,
    val atMillis: Long = 0L,
    val accepted: Boolean = false,
)

object HeatPoints {

    /**
     * Oferta do histórico → ponto do mapa. null quando o ponto escolhido não tem coordenada
     * (endereço não lido ou não localizado pelo Geocoder).
     */
    fun of(offer: EvaluatedOffer, accepted: Boolean?, spot: HeatSpot): HeatPoint? {
        val p = when (spot) {
            HeatSpot.PICKUP -> offer.pickup
            HeatSpot.DROPOFF -> offer.dropoff
        } ?: return null
        if (!p.lat.isFinite() || !p.lon.isFinite()) return null
        if (p.lat == 0.0 && p.lon == 0.0) return null
        if (p.lat < -90.0 || p.lat > 90.0 || p.lon < -180.0 || p.lon > 180.0) return null
        return HeatPoint(
            point = p,
            price = offer.raw.price,
            perKm = offer.metrics.perKm?.takeIf { it.isFinite() },
            perHour = offer.metrics.perHour?.takeIf { it.isFinite() },
            profit = offer.metrics.netProfit?.takeIf { it.isFinite() },
            platform = offer.raw.platform,
            category = offer.raw.category?.trim()?.takeIf { it.isNotEmpty() },
            atMillis = offer.raw.capturedAt,
            accepted = accepted == true,
        )
    }

    /** Aplica os filtros da aba (período, horário, app, modalidade, todas/só aceitas). */
    fun filter(
        points: List<HeatPoint>,
        rules: HeatFilter,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HeatPoint> {
        val from = rules.period.from(now)
        return points.filter { keep(it, rules, from, zone) }
    }

    private fun keep(p: HeatPoint, rules: HeatFilter, from: Long, zone: ZoneId): Boolean {
        if (p.atMillis < from) return false
        if (rules.set == HeatSet.ACCEPTED && !p.accepted) return false
        if (rules.platforms.isNotEmpty() && p.platform !in rules.platforms) return false
        if (rules.categories.isNotEmpty() && (p.category == null || p.category !in rules.categories)) return false
        val hours = rules.hours
        if (hours != null && !hours.isAll) {
            val hour = Instant.ofEpochMilli(p.atMillis).atZone(zone).hour
            if (!hours.contains(hour)) return false
        }
        return true
    }

    /** Modalidades vistas no histórico, da mais comum para a menos (para os botões de filtro). */
    fun categories(points: List<HeatPoint>): List<String> =
        points.mapNotNull { it.category }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
}

/** Cores do mapa de calor (ARGB, como [br.com.mapp.core.model.ZoneType.defaultColorArgb]). */
object HeatPalette {

    /** Quantos degraus a legenda mostra (a cor em si é contínua). */
    const val LEVELS = 5

    /** Densidade de chamadas: amarelo claro → vermelho escuro (as cores do mapa da 99). */
    private val DEMAND = listOf(0xFFE082L, 0xFFB300L, 0xFB8C00L, 0xE53935L, 0xB71C1CL)

    /** Dinheiro: vermelho (pouco) → verde (muito). */
    private val MONEY = listOf(0xE53935L, 0xFB8C00L, 0xFDD835L, 0x9CCC65L, 0x2E7D32L)

    // A borda da mancha fica quase transparente e o miolo forte: é isso que dá o ar esfumaçado,
    // sem desenhar sombra nenhuma. No dinheiro o "pouco" também interessa, então o mínimo é maior.
    private const val DEMAND_ALPHA_MIN = 0x2E
    private const val DEMAND_ALPHA_MAX = 0xCC
    private const val MONEY_ALPHA_MIN = 0x66
    private const val MONEY_ALPHA_MAX = 0xCC

    /** Cor fixa por modalidade, a mesma da barra de tipos do Histórico de jornadas. */
    val CATEGORY_COLORS: Map<String, Long> = linkedMapOf(
        "UberX" to 0xFF4FC3F7L,
        "Comfort" to 0xFF4DD0B8L,
        "Black" to 0xFFB0BEC5L,
        "Electric" to 0xFFAED581L,
        "Prioridade" to 0xFFFFB74DL,
        "Flash" to 0xFFFFF176L,
        "Moto" to 0xFFE57373L,
        "Juntos" to 0xFFBA68C8L,
        "Bag" to 0xFFA1887FL,
        "Pop" to 0xFFFFD54FL,
        "Pop Expresso" to 0xFFFFA726L,
        "Táxi" to 0xFFFF8A65L,
    )

    /** Cores livres para modalidades que não estão na lista fixa. */
    val EXTRA_CATEGORY_COLORS: List<Long> = listOf(
        0xFFB39DDBL,
        0xFFF06292L,
        0xFF4DD0E1L,
        0xFF7986CBL,
        0xFF81C784L,
        0xFFFF8A65L,
    )

    /** Cor do hexágono para a fração 0..1 da escala, com a transparência que faz a mancha. */
    fun color(metric: HeatMetric, fraction: Double): Long {
        val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        val rgb = mix(if (metric.isMoney) MONEY else DEMAND, f)
        val min = if (metric.isMoney) MONEY_ALPHA_MIN else DEMAND_ALPHA_MIN
        val max = if (metric.isMoney) MONEY_ALPHA_MAX else DEMAND_ALPHA_MAX
        val alpha = (min + (max - min) * f).toLong().coerceIn(0L, 255L)
        return (alpha shl 24) or rgb
    }

    /** Cor de um dos degraus da legenda (0 = frio, [LEVELS] - 1 = quente). */
    fun levelColor(metric: HeatMetric, level: Int): Long =
        color(metric, level.coerceIn(0, LEVELS - 1).toDouble() / (LEVELS - 1))

    /** A mesma cor, opaca: para a bolinha da lista e os quadradinhos da legenda. */
    fun solid(color: Long): Long = color or 0xFF000000L

    /** Uma cor por modalidade, na ordem em que aparecem (lista fixa primeiro). */
    fun categoryColors(names: List<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        names.forEach { n -> CATEGORY_COLORS[n]?.let { out[n] = it } }
        var next = 0
        names.filter { it !in out }.forEach { n ->
            val free = EXTRA_CATEGORY_COLORS.firstOrNull { it !in out.values }
            out[n] = free ?: EXTRA_CATEGORY_COLORS[next++ % EXTRA_CATEGORY_COLORS.size]
        }
        return out
    }

    /** Hexágono com cor de modalidade: mais fraco no pouco, mais forte no muito. */
    fun categoryColor(color: Long, fraction: Double): Long {
        val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        val alpha = (DEMAND_ALPHA_MIN + (DEMAND_ALPHA_MAX - DEMAND_ALPHA_MIN) * f).toLong().coerceIn(0L, 255L)
        return (alpha shl 24) or (color and 0x00FFFFFFL)
    }

    private fun mix(stops: List<Long>, f: Double): Long {
        if (f <= 0.0) return stops.first()
        if (f >= 1.0) return stops.last()
        val pos = f * (stops.size - 1)
        val i = pos.toInt().coerceIn(0, stops.size - 2)
        return blend(stops[i], stops[i + 1], pos - i)
    }

    private fun blend(a: Long, b: Long, t: Double): Long {
        val r = channel(a, 16, b, t)
        val g = channel(a, 8, b, t)
        val bl = channel(a, 0, b, t)
        return (r shl 16) or (g shl 8) or bl
    }

    private fun channel(a: Long, shift: Int, b: Long, t: Double): Long {
        val av = (a shr shift) and 0xFFL
        val bv = (b shr shift) and 0xFFL
        return (av + (bv - av) * t).toLong().coerceIn(0L, 255L)
    }
}
