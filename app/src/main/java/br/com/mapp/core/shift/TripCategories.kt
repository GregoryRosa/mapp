package br.com.mapp.core.shift

import br.com.mapp.core.model.RawOffer
import kotlin.math.abs

/**
 * Tipo da corrida (UberX, Comfort...) que ficou sem leitura na jornada — o OCR nem sempre pega o tipo
 * no 1º quadro, ou a corrida foi contada de uma oferta restaurada — completado pela oferta do
 * histórico: mesmo app e mesmo valor, vista até [LOOKBACK_MS] antes do aceite (corrida aceita em fila
 * durante outra viagem) ou até [AHEAD_MS] depois. Vale a oferta mais perto do aceite.
 */
object TripCategories {
    const val LOOKBACK_MS = 60 * 60_000L
    const val AHEAD_MS = 2 * 60_000L

    fun categoryFor(trip: ShiftTrip, offers: List<RawOffer>): String? =
        offers.asSequence()
            .filter { !it.category.isNullOrBlank() && it.platform == trip.platform && abs(it.price - trip.price) < 0.005 }
            .filter { it.capturedAt in (trip.atMillis - LOOKBACK_MS)..(trip.atMillis + AHEAD_MS) }
            .minByOrNull { abs(trip.atMillis - it.capturedAt) }
            ?.category

    /** A jornada com os tipos que faltavam preenchidos (a mesma instância se nada mudou). */
    fun fill(shift: Shift, offers: List<RawOffer>): Shift {
        if (shift.trips.none { it.category.isNullOrBlank() }) return shift
        val trips = shift.trips.map { t ->
            if (!t.category.isNullOrBlank()) t else categoryFor(t, offers)?.let { t.copy(category = it) } ?: t
        }
        return if (trips == shift.trips) shift else shift.copy(trips = trips)
    }
}
