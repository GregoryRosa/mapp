package br.com.mapp.core.route

import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.RawOffer
import kotlin.math.max

enum class StopKind { PICKUP, STOP, DROPOFF }

/**
 * Ponto da corrida na ordem do trajeto, com a letra do mapa: A = coleta, B, C… = paradas no
 * caminho, a letra seguinte = destino final. [point] null = não localizado (fica fora do mapa,
 * mas a letra é mantida para as outras não mudarem).
 */
data class RouteStop(val letter: String, val kind: StopKind, val address: String?, val point: GeoPoint?)

/** Sequência coleta → paradas → destino, usada no mini mapa do Card e no mapa da notificação. */
object RideRoute {

    /** Nº de paradas no caminho: o anunciado no cartão ou o de endereços lidos (o maior). */
    fun stopCount(raw: RawOffer): Int = max(raw.stopCount, raw.stopAddresses.size).coerceAtLeast(0)

    fun of(offer: EvaluatedOffer): List<RouteStop> = of(offer.raw, offer.pickup, offer.stops, offer.dropoff)

    /**
     * Coleta e destino entram sempre; entre eles, uma entrada por parada (com ou sem endereço/ponto).
     * Com 1 parada: A coleta, B parada, C destino.
     */
    fun of(raw: RawOffer, pickup: GeoPoint?, stopPoints: List<GeoPoint?>, dropoff: GeoPoint?): List<RouteStop> {
        val n = stopCount(raw)
        val route = ArrayList<RouteStop>(n + 2)
        route += RouteStop(letter(0), StopKind.PICKUP, clean(raw.pickupAddress), pickup)
        for (i in 0 until n) {
            route += RouteStop(letter(i + 1), StopKind.STOP, clean(raw.stopAddresses.getOrNull(i)), stopPoints.getOrNull(i))
        }
        route += RouteStop(letter(n + 1), StopKind.DROPOFF, clean(raw.dropoffAddress), dropoff)
        return route
    }

    /** A, B, C… (depois do Z, números). */
    fun letter(index: Int): String = if (index in 0..25) ('A' + index).toString() else "${index + 1}"

    /** "1 parada" / "2 paradas"; null quando a corrida é direta. */
    fun stopsLabel(raw: RawOffer): String? = when (val n = stopCount(raw)) {
        0 -> null
        1 -> "1 parada"
        else -> "$n paradas"
    }

    private fun clean(address: String?): String? = address?.trim()?.takeIf { it.isNotEmpty() }
}
