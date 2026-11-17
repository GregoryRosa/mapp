package br.com.mapp.core.heat

import br.com.mapp.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * Quadradinho da grade com o resumo das ofertas que caíram dentro dele.
 * [x] e [y] são índices da grade global (x = longitude, y = latitude).
 */
data class HeatCell(
    val x: Int,
    val y: Int,
    val count: Int,
    val accepted: Int,
    val avgPrice: Double,
    val avgPerKm: Double? = null,
    val avgPerHour: Double? = null,
    val avgProfit: Double? = null,
    val topCategory: String? = null,
    val center: GeoPoint = GeoPoint(0.0, 0.0),
    val southWest: GeoPoint = GeoPoint(0.0, 0.0),
    val northEast: GeoPoint = GeoPoint(0.0, 0.0),
) {
    /** Valor da métrica escolhida; null = o quadradinho não tem esse número lido (não pinta). */
    fun value(metric: HeatMetric): Double? = when (metric) {
        HeatMetric.CALLS -> count.toDouble()
        HeatMetric.CATEGORY -> count.toDouble()
        HeatMetric.PER_KM -> avgPerKm
        HeatMetric.PER_HOUR -> avgPerHour
        HeatMetric.PROFIT -> avgProfit
        HeatMetric.PRICE -> avgPrice
    }

    /** Os 4 cantos, em ordem, para desenhar ou salvar como zona. */
    val polygon: List<GeoPoint>
        get() = listOf(
            GeoPoint(southWest.lat, southWest.lon),
            GeoPoint(southWest.lat, northEast.lon),
            GeoPoint(northEast.lat, northEast.lon),
            GeoPoint(northEast.lat, southWest.lon),
        )
}

/**
 * A grade inteira de um filtro: quadradinhos pintados + o tamanho deles.
 * [considered] = ofertas que entraram na conta; [plotted] = as que caíram em quadradinho pintado
 * (quadradinho com poucas ofertas não pinta, para uma corrida sortuda não virar zona de ouro).
 */
data class HeatMap(
    val cells: List<HeatCell>,
    val latStep: Double,
    val lonStep: Double,
    val meters: Double,
    val considered: Int,
    val plotted: Int,
) {
    val isEmpty: Boolean get() = cells.isEmpty()

    /** Canto sudoeste do quadradinho de índice [x], [y] (também vale para os cantos da grade). */
    fun corner(x: Int, y: Int): GeoPoint = GeoPoint(y * latStep, x * lonStep)

    companion object {
        val EMPTY = HeatMap(emptyList(), 0.0, 0.0, 0.0, 0, 0)
    }
}

/**
 * Grade do mapa de calor. O lado do quadradinho muda com o zoom: longe junta mais (bairro),
 * perto separa (quadra).
 */
object HeatGrid {

    /** Mínimo de ofertas no quadradinho para ele pintar. */
    const val MIN_SAMPLES = 3

    private const val METERS_PER_DEGREE = 111_320.0

    /** Lado do quadradinho, em metros, para o zoom atual do mapa. */
    fun cellMeters(zoom: Double): Double = when {
        zoom < 11.0 -> 2_000.0
        zoom < 12.0 -> 1_500.0
        zoom < 13.0 -> 1_000.0
        zoom < 14.0 -> 600.0
        zoom < 15.0 -> 400.0
        zoom < 16.0 -> 250.0
        zoom < 17.0 -> 150.0
        else -> 100.0
    }

    fun latStep(meters: Double): Double = meters / METERS_PER_DEGREE

    /** Passo da longitude na latitude de referência, para o quadradinho sair quadrado na tela. */
    fun lonStep(meters: Double, refLat: Double): Double {
        val c = abs(cos(refLat * PI / 180.0))
        return meters / (METERS_PER_DEGREE * if (c < 0.01) 0.01 else c)
    }

    /** Agrupa os pontos em quadradinhos de [meters] metros de lado. */
    fun build(points: List<HeatPoint>, meters: Double, minSamples: Int = MIN_SAMPLES): HeatMap {
        if (points.isEmpty() || meters <= 0.0) return HeatMap.EMPTY
        val refLat = points.sumOf { it.point.lat } / points.size
        val dLat = latStep(meters)
        val dLon = lonStep(meters, refLat)
        val groups = LinkedHashMap<Long, MutableList<HeatPoint>>()
        points.forEach { p ->
            val x = floor(p.point.lon / dLon).toInt()
            val y = floor(p.point.lat / dLat).toInt()
            groups.getOrPut(key(x, y)) { mutableListOf() }.add(p)
        }
        val cells = groups.entries
            .filter { it.value.size >= minSamples }
            .map { cell(xOf(it.key), yOf(it.key), it.value, dLat, dLon) }
            .sortedWith(compareByDescending<HeatCell> { it.count }.thenBy { it.y }.thenBy { it.x })
        return HeatMap(
            cells = cells,
            latStep = dLat,
            lonStep = dLon,
            meters = meters,
            considered = points.size,
            plotted = cells.sumOf { it.count },
        )
    }

    private fun cell(x: Int, y: Int, list: List<HeatPoint>, dLat: Double, dLon: Double): HeatCell {
        val sw = GeoPoint(y * dLat, x * dLon)
        val ne = GeoPoint((y + 1) * dLat, (x + 1) * dLon)
        return HeatCell(
            x = x,
            y = y,
            count = list.size,
            accepted = list.count { it.accepted },
            avgPrice = list.sumOf { it.price } / list.size,
            avgPerKm = avg(list.mapNotNull { it.perKm }),
            avgPerHour = avg(list.mapNotNull { it.perHour }),
            avgProfit = avg(list.mapNotNull { it.profit }),
            topCategory = top(list.mapNotNull { it.category }),
            center = GeoPoint((sw.lat + ne.lat) / 2.0, (sw.lon + ne.lon) / 2.0),
            southWest = sw,
            northEast = ne,
        )
    }

    private fun avg(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.sum() / values.size

    private fun top(names: List<String>): String? =
        names.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun xOf(key: Long): Int = (key shr 32).toInt()

    private fun yOf(key: Long): Int = key.toInt()
}

/**
 * Escala de cor da métrica, em 5 níveis (0 = frio, 4 = quente).
 *
 * **Chamadas** usa escala absoluta (o quadradinho com mais chamadas é o mais quente).
 * **Dinheiro** usa a posição do quadradinho na fila (quantil): responde "onde paga melhor" mesmo
 * quando todos os valores são parecidos. Se nada se destaca, tudo fica no nível do meio e nenhuma
 * zona é detectada — é a resposta honesta.
 */
class HeatScale private constructor(
    private val sorted: List<Double>,
    private val quantile: Boolean,
) {
    val min: Double get() = sorted.first()
    val max: Double get() = sorted.last()
    val size: Int get() = sorted.size

    fun level(value: Double): Int {
        if (max - min <= EPS) return HeatPalette.LEVELS / 2
        if (!quantile) {
            if (max <= 0.0) return 0
            val f = value / max
            return (f * HeatPalette.LEVELS).toInt().coerceIn(0, HeatPalette.LEVELS - 1)
        }
        // Com poucos quadradinhos o quantil não separa nada: o melhor deles é sempre o mais quente.
        if (value >= max - EPS) return HeatPalette.LEVELS - 1
        val below = sorted.count { it < value }
        return (below.toDouble() / sorted.size * HeatPalette.LEVELS)
            .toInt()
            .coerceIn(0, HeatPalette.LEVELS - 1)
    }

    fun level(cell: HeatCell, metric: HeatMetric): Int? = cell.value(metric)?.let { level(it) }

    /** O quadradinho está quente o bastante para virar zona? */
    fun isHot(cell: HeatCell, metric: HeatMetric): Boolean = (level(cell, metric) ?: -1) >= HOT_LEVEL

    companion object {
        /** Nível a partir do qual o quadradinho entra numa zona automática. */
        const val HOT_LEVEL = 3
        private const val EPS = 1e-9

        /** null quando nenhum quadradinho tem a métrica lida (nada a pintar). */
        fun of(cells: List<HeatCell>, metric: HeatMetric): HeatScale? {
            val values = cells.mapNotNull { it.value(metric) }.filter { it.isFinite() }
            if (values.isEmpty()) return null
            return HeatScale(values.sorted(), quantile = metric.isMoney)
        }
    }
}
