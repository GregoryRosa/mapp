package br.com.mapp.core.geo

import br.com.mapp.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0

    fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
    }

    /** Ray casting. Polígonos com menos de 3 pontos nunca contêm o ponto. */
    fun contains(polygon: List<GeoPoint>, p: GeoPoint): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val crosses = (pi.lat > p.lat) != (pj.lat > p.lat) &&
                p.lon < (pj.lon - pi.lon) * (p.lat - pi.lat) / (pj.lat - pi.lat) + pi.lon
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }
}
