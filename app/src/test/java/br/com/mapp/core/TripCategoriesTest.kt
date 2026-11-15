package br.com.mapp.core

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftTrip
import br.com.mapp.core.shift.TripCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TripCategoriesTest {
    private val min = 60_000L

    private fun trip(at: Long, price: Double, category: String? = null, platform: Platform = Platform.UBER) =
        ShiftTrip(atMillis = at, platform = platform, category = category, price = price, pickupKm = 1.0, tripKm = 5.0)

    private fun offer(at: Long, price: Double, category: String?, platform: Platform = Platform.UBER) =
        RawOffer(platform = platform, price = price, category = category, capturedAt = at)

    @Test fun completa_o_tipo_pela_oferta_do_historico() {
        val offers = listOf(offer(100 * min, 19.13, "Comfort"), offer(90 * min, 7.69, "UberX"))
        assertEquals("Comfort", TripCategories.categoryFor(trip(101 * min, 19.13), offers))
    }

    @Test fun valor_ou_app_diferente_nao_serve() {
        val offers = listOf(offer(100 * min, 19.13, "Comfort", Platform.NINETY_NINE), offer(100 * min, 19.14, "Black"))
        assertNull(TripCategories.categoryFor(trip(101 * min, 19.13), offers))
    }

    @Test fun oferta_aceita_em_fila_ate_60_min_antes() {
        assertEquals("Black", TripCategories.categoryFor(trip(150 * min, 30.0), listOf(offer(100 * min, 30.0, "Black"))))
        assertNull(TripCategories.categoryFor(trip(170 * min, 30.0), listOf(offer(100 * min, 30.0, "Black"))))
    }

    @Test fun vale_a_oferta_mais_perto_do_aceite() {
        val offers = listOf(offer(60 * min, 12.0, "UberX"), offer(99 * min, 12.0, "Electric"))
        assertEquals("Electric", TripCategories.categoryFor(trip(100 * min, 12.0), offers))
    }

    @Test fun oferta_sem_tipo_nao_conta() {
        val offers = listOf(offer(99 * min, 12.0, null), offer(80 * min, 12.0, "UberX"))
        assertEquals("UberX", TripCategories.categoryFor(trip(100 * min, 12.0), offers))
    }

    @Test fun preenche_so_as_corridas_sem_tipo() {
        val shift = Shift(
            startedAt = 0,
            trips = listOf(trip(10 * min, 15.0, "Black"), trip(20 * min, 12.0), trip(30 * min, 9.0)),
        )
        val filled = TripCategories.fill(shift, listOf(offer(19 * min, 12.0, "Comfort"), offer(9 * min, 15.0, "UberX")))
        assertEquals(listOf("Black", "Comfort", null), filled.trips.map { it.category })
        val done = Shift(startedAt = 0, trips = listOf(trip(10 * min, 15.0, "Black")))
        assertSame(done, TripCategories.fill(done, emptyList()))
    }
}
