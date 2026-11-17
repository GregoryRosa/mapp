package br.com.mapp.core.heat

import br.com.mapp.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Ponto no plano local, em metros (x para o leste, y para o norte). */
data class PlanePoint(val x: Double, val y: Double)

/**
 * Projeção plana local: metros em volta de um ponto de referência. Dentro de uma cidade o erro é
 * desprezível, e é o que deixa a conta dos hexágonos ser feita em metros, sem trigonometria a cada
 * ponto.
 */
class HeatPlane(val lat0: Double, val lon0: Double) {

    private val metersPerLon: Double =
        METERS_PER_DEGREE * cos(lat0 * PI / 180.0).let { if (abs(it) < 0.01) 0.01 else abs(it) }

    /** Coordenada → metros no plano. */
    fun xy(p: GeoPoint): PlanePoint =
        PlanePoint((p.lon - lon0) * metersPerLon, (p.lat - lat0) * METERS_PER_DEGREE)

    fun geo(x: Double, y: Double): GeoPoint =
        GeoPoint(lat0 + y / METERS_PER_DEGREE, lon0 + x / metersPerLon)

    fun geo(p: PlanePoint): GeoPoint = geo(p.x, p.y)

    companion object {
        const val METERS_PER_DEGREE = 111_320.0

        /** Plano ancorado na média dos pontos: andar com o mapa não mexe na grade. */
        fun of(points: List<GeoPoint>): HeatPlane {
            if (points.isEmpty()) return HeatPlane(0.0, 0.0)
            return HeatPlane(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
        }
    }
}

/**
 * Grade de hexágonos de ponta para cima, em coordenadas axiais (q, r) — o mesmo formato que a 99 e a
 * Uber usam no mapa de demanda. [size] é o raio do hexágono (centro até o canto), em metros:
 * a largura é `√3 · size` e a altura `2 · size`.
 */
object HexMath {

    val SQRT3: Double = sqrt(3.0)

    /** Vizinhos de um hexágono (os 6 que dividem um lado com ele). */
    val NEIGHBORS: List<Pair<Int, Int>> = listOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)

    fun center(q: Int, r: Int, size: Double): PlanePoint =
        PlanePoint(size * SQRT3 * (q + r / 2.0), size * 1.5 * r)

    /** Em que hexágono o ponto cai. */
    fun cellOf(p: PlanePoint, size: Double): Pair<Int, Int> {
        val q = (SQRT3 / 3.0 * p.x - p.y / 3.0) / size
        val r = (2.0 / 3.0 * p.y) / size
        return roundAxial(q, r)
    }

    /** Arredonda coordenada fracionária para o hexágono mais perto (arredondamento em cubo). */
    fun roundAxial(qf: Double, rf: Double): Pair<Int, Int> {
        val sf = -qf - rf
        var q = round(qf).toInt()
        var r = round(rf).toInt()
        val s = round(sf).toInt()
        val dq = abs(q - qf)
        val dr = abs(r - rf)
        val ds = abs(s - sf)
        if (dq > dr && dq > ds) {
            q = -r - s
        } else if (dr > ds) {
            r = -q - s
        }
        return q to r
    }

    /** Os 6 cantos, no sentido anti-horário. */
    fun corners(q: Int, r: Int, size: Double): List<PlanePoint> {
        val c = center(q, r, size)
        return (0 until 6).map { i ->
            val angle = PI / 180.0 * (60.0 * i - 30.0)
            PlanePoint(c.x + size * cos(angle), c.y + size * sin(angle))
        }
    }

    /** Quantos anéis de hexágonos cabem num raio em metros (para varrer a vizinhança de um ponto). */
    fun rings(radiusMeters: Double, size: Double): Int {
        if (size <= 0.0) return 0
        return ceil(radiusMeters / (SQRT3 * size)).toInt() + 1
    }

    /** Todos os hexágonos a até [rings] anéis de (q, r), incluindo ele. */
    fun around(q: Int, r: Int, rings: Int): List<Pair<Int, Int>> {
        if (rings <= 0) return listOf(q to r)
        val out = ArrayList<Pair<Int, Int>>((2 * rings + 1) * (2 * rings + 1))
        for (dq in -rings..rings) {
            val from = maxOf(-rings, -dq - rings)
            val to = minOf(rings, -dq + rings)
            for (dr in from..to) out.add((q + dq) to (r + dr))
        }
        return out
    }

    fun key(q: Int, r: Int): Long = (q.toLong() shl 32) or (r.toLong() and 0xFFFFFFFFL)

    fun qOf(key: Long): Int = (key shr 32).toInt()

    fun rOf(key: Long): Int = key.toInt()
}
