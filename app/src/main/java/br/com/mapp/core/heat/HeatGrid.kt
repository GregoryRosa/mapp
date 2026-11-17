package br.com.mapp.core.heat

import br.com.mapp.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Um hexágono da grade, com o que foi lido dentro e em volta dele.
 *
 * [count] são as ofertas que caem **dentro** deste hexágono (cada oferta conta uma vez só, num
 * hexágono só) — é o número mostrado na zona. [weight] é a densidade **suavizada**: as ofertas da
 * vizinhança entram com peso que cai com a distância, e é ela que dá a cor. É o que faz a mancha
 * aparecer esfumaçada, como no mapa da 99, em vez de um quadriculado duro.
 */
data class HeatCell(
    val q: Int,
    val r: Int,
    val count: Int,
    val accepted: Int,
    val weight: Double,
    val avgPrice: Double? = null,
    val avgPerKm: Double? = null,
    val avgPerHour: Double? = null,
    val avgProfit: Double? = null,
    val topCategory: String? = null,
    val center: GeoPoint = GeoPoint(0.0, 0.0),
    val polygon: List<GeoPoint> = emptyList(),
) {
    /** Valor da métrica escolhida; null = a vizinhança não tem esse número lido (não pinta). */
    fun value(metric: HeatMetric): Double? = when (metric) {
        HeatMetric.CALLS -> weight
        HeatMetric.CATEGORY -> weight
        HeatMetric.PER_KM -> avgPerKm
        HeatMetric.PER_HOUR -> avgPerHour
        HeatMetric.PROFIT -> avgProfit
        HeatMetric.PRICE -> avgPrice
    }

    val key: Long get() = HexMath.key(q, r)
}

/**
 * A grade inteira de um filtro: hexágonos pintados, o tamanho deles e o raio da suavização.
 * [considered] = ofertas que entraram na conta; [plotted] = as que caíram em hexágono pintado.
 */
data class HeatMap(
    val cells: List<HeatCell>,
    val plane: HeatPlane,
    val sizeMeters: Double,
    val kernelMeters: Double,
    val considered: Int,
    val plotted: Int,
) {
    val isEmpty: Boolean get() = cells.isEmpty()

    companion object {
        val EMPTY = HeatMap(emptyList(), HeatPlane(0.0, 0.0), 0.0, 0.0, 0, 0)
    }
}

/**
 * Monta a grade de hexágonos do mapa de calor.
 *
 * O hexágono tem **sempre o mesmo tamanho na tela** (como no mapa da 99): quem muda é quanto chão
 * ele cobre. Dando zoom, ele passa a cobrir menos metros e a mancha fica mais específica, sem
 * crescer na tela.
 */
object HeatGrid {

    /** Largura do hexágono na tela, em dp (vale para qualquer zoom). */
    const val TARGET_DP = 22.0

    /** Limites do tamanho do hexágono no chão: nem fino demais para o pouco dado, nem gigante. */
    const val MIN_SIZE_M = 25.0
    const val MAX_SIZE_M = 4_000.0

    /** Raio mínimo da suavização: abaixo disso a mancha vira pontinho solto. */
    const val MIN_KERNEL_M = 150.0

    /** Quantas ofertas por perto (já com o peso da distância) para o hexágono pintar. */
    const val MIN_WEIGHT = 1.5

    /**
     * Metros por dp do mapa no zoom e na latitude dados. Com `setTilesScaledToDpi` a densidade da
     * tela se cancela, então a conta vale para qualquer aparelho.
     */
    fun metersPerDp(zoom: Double, lat: Double): Double =
        156_543.03392 * cos(lat * PI / 180.0) / 2.0.pow(zoom)

    /** Raio do hexágono (centro até o canto) para ele sair com [TARGET_DP] de largura na tela. */
    fun sizeMeters(zoom: Double, lat: Double, targetDp: Double = TARGET_DP): Double =
        (targetDp * metersPerDp(zoom, lat) / HexMath.SQRT3).coerceIn(MIN_SIZE_M, MAX_SIZE_M)

    /** Raio da suavização: acompanha o hexágono, com um mínimo para a mancha nunca virar ruído. */
    fun kernelMeters(size: Double): Double = max(size * 3.0, MIN_KERNEL_M)

    /** Monta a grade para o zoom atual do mapa. */
    fun build(points: List<HeatPoint>, zoom: Double, targetDp: Double = TARGET_DP): HeatMap {
        if (points.isEmpty()) return HeatMap.EMPTY
        val plane = HeatPlane.of(points.map { it.point })
        val size = sizeMeters(zoom, plane.lat0, targetDp)
        return build(points, plane, size, kernelMeters(size))
    }

    /** Monta a grade com tamanho e suavização escolhidos (usado também pelos testes). */
    fun build(
        points: List<HeatPoint>,
        plane: HeatPlane,
        size: Double,
        kernel: Double,
        minWeight: Double = MIN_WEIGHT,
    ): HeatMap {
        if (points.isEmpty() || size <= 0.0) return HeatMap.EMPTY
        val rings = HexMath.rings(kernel, size)
        val acc = HashMap<Long, Acc>(points.size * 4)

        points.forEach { p ->
            val xy = plane.xy(p.point)
            val (hq, hr) = HexMath.cellOf(xy, size)
            // A oferta conta uma vez só, no hexágono em que ela cai.
            acc.getOrPut(HexMath.key(hq, hr)) { Acc() }.own(p)
            // E espalha o peso pela vizinhança, que é o que deixa a mancha macia.
            HexMath.around(hq, hr, rings).forEach { (q, r) ->
                val c = HexMath.center(q, r, size)
                val dx = c.x - xy.x
                val dy = c.y - xy.y
                val w = weightOf(sqrt(dx * dx + dy * dy), kernel)
                if (w > 0.0) acc.getOrPut(HexMath.key(q, r)) { Acc() }.add(p, w)
            }
        }

        val cells = acc.entries
            .filter { it.value.weight >= minWeight }
            .map { (key, a) -> a.toCell(HexMath.qOf(key), HexMath.rOf(key), plane, size) }
            .sortedWith(compareByDescending<HeatCell> { it.weight }.thenBy { it.r }.thenBy { it.q })

        return HeatMap(
            cells = cells,
            plane = plane,
            sizeMeters = size,
            kernelMeters = kernel,
            considered = points.size,
            plotted = cells.sumOf { it.count },
        )
    }

    /**
     * Peso de uma oferta a [distance] metros do centro do hexágono (biquadrado): 1 no centro, 0 na
     * borda do raio, caindo macio no meio.
     */
    fun weightOf(distance: Double, kernel: Double): Double {
        if (kernel <= 0.0 || distance >= kernel) return 0.0
        val u = distance / kernel
        val t = 1.0 - u * u
        return t * t
    }

    /** Soma dos pesos enquanto a grade é montada. */
    private class Acc {
        var count = 0
        var accepted = 0
        var weight = 0.0
        private var priceSum = 0.0
        private var priceWeight = 0.0
        private var perKmSum = 0.0
        private var perKmWeight = 0.0
        private var perHourSum = 0.0
        private var perHourWeight = 0.0
        private var profitSum = 0.0
        private var profitWeight = 0.0
        private val categories = HashMap<String, Double>()

        fun own(p: HeatPoint) {
            count += 1
            if (p.accepted) accepted += 1
        }

        fun add(p: HeatPoint, w: Double) {
            weight += w
            priceSum += p.price * w
            priceWeight += w
            p.perKm?.let { perKmSum += it * w; perKmWeight += w }
            p.perHour?.let { perHourSum += it * w; perHourWeight += w }
            p.profit?.let { profitSum += it * w; profitWeight += w }
            p.category?.let { categories[it] = (categories[it] ?: 0.0) + w }
        }

        fun toCell(q: Int, r: Int, plane: HeatPlane, size: Double): HeatCell = HeatCell(
            q = q,
            r = r,
            count = count,
            accepted = accepted,
            weight = weight,
            avgPrice = mean(priceSum, priceWeight),
            avgPerKm = mean(perKmSum, perKmWeight),
            avgPerHour = mean(perHourSum, perHourWeight),
            avgProfit = mean(profitSum, profitWeight),
            topCategory = categories.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key,
            center = plane.geo(HexMath.center(q, r, size)),
            polygon = HexMath.corners(q, r, size).map { plane.geo(it) },
        )

        private fun mean(sum: Double, weight: Double): Double? = if (weight <= 0.0) null else sum / weight
    }
}

/**
 * Escala de cor da métrica.
 *
 * **Chamadas** usa escala absoluta (o hexágono mais cheio é o mais quente). **Dinheiro** usa a
 * posição do hexágono na fila (quantil): responde "onde paga melhor" mesmo quando os valores são
 * parecidos. Se nada se destaca, tudo fica no meio e nenhuma zona é detectada — é a resposta honesta.
 */
class HeatScale private constructor(
    private val sorted: List<Double>,
    private val quantile: Boolean,
) {
    val min: Double get() = sorted.first()
    val max: Double get() = sorted.last()
    val size: Int get() = sorted.size

    /** 0 (frio) a 1 (quente): é com ela que a cor é misturada. */
    fun fraction(value: Double): Double {
        if (max - min <= EPS) return 0.5
        if (!quantile) {
            if (max <= 0.0) return 0.0
            return (value / max).coerceIn(0.0, 1.0)
        }
        if (value >= max - EPS) return 1.0
        val below = sorted.count { it < value }
        return (below.toDouble() / sorted.size).coerceIn(0.0, 1.0)
    }

    fun fraction(cell: HeatCell, metric: HeatMetric): Double? = cell.value(metric)?.let { fraction(it) }

    fun level(value: Double): Int =
        (fraction(value) * HeatPalette.LEVELS).toInt().coerceIn(0, HeatPalette.LEVELS - 1)

    fun level(cell: HeatCell, metric: HeatMetric): Int? = cell.value(metric)?.let { level(it) }

    /** O hexágono está quente o bastante para entrar numa zona? */
    fun isHot(cell: HeatCell, metric: HeatMetric): Boolean = (fraction(cell, metric) ?: 0.0) >= HOT

    companion object {
        /** Fração a partir da qual o hexágono entra numa zona automática. */
        const val HOT = 0.6
        private const val EPS = 1e-9

        /** null quando nenhum hexágono tem a métrica lida (nada a pintar). */
        fun of(cells: List<HeatCell>, metric: HeatMetric): HeatScale? {
            val values = cells.mapNotNull { it.value(metric) }.filter { it.isFinite() }
            if (values.isEmpty()) return null
            return HeatScale(values.sorted(), quantile = metric.isMoney)
        }
    }
}
