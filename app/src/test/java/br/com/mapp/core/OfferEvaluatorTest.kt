package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferEvaluatorTest {
    private val cost = VehicleCostConfig(fuelPricePerLiter = 6.0, kmPerLiter = 12.0, maintenancePerKm = 0.1, depreciationPerKm = 0.1)
    private val profile = FilterProfile(minPerKm = 1.5, goodPerKm = 2.0, minPerHour = 30.0, goodPerHour = 45.0, maxPickupKm = 4.0)
    private val square = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(1.0, 1.0), GeoPoint(1.0, 0.0))

    @Test fun metricas_somam_busca_e_viagem() {
        val m = OfferEvaluator.metrics(RawOffer(Platform.UBER, 20.0, tripKm = 8.0, tripMin = 15, pickupKm = 2.0, pickupMin = 5), cost)
        assertEquals(10.0, m.totalKm!!, 1e-9)
        assertEquals(2.0, m.perKm!!, 1e-9)
        assertEquals(60.0, m.perHour!!, 1e-9)
        assertEquals(20.0 - 10.0 * 0.7, m.netProfit!!, 1e-9)
    }

    @Test fun oferta_boa_fica_verde() {
        val e = OfferEvaluator.evaluate(RawOffer(Platform.UBER, 25.0, tripKm = 9.0, tripMin = 15, pickupKm = 1.0, pickupMin = 3), profile, cost)
        assertEquals(Signal.GREEN, e.signal)
    }

    @Test fun oferta_media_fica_amarela() {
        val e = OfferEvaluator.evaluate(RawOffer(Platform.NINETY_NINE, 17.0, tripKm = 9.0, tripMin = 20, pickupKm = 1.0, pickupMin = 3), profile, cost)
        assertEquals(Signal.YELLOW, e.signal)
    }

    @Test fun busca_longe_fica_vermelha() {
        val e = OfferEvaluator.evaluate(RawOffer(Platform.INDRIVE, 30.0, tripKm = 10.0, tripMin = 15, pickupKm = 6.0, pickupMin = 10), profile, cost)
        assertEquals(Signal.RED, e.signal)
    }

    @Test fun coleta_em_zona_perigosa_fica_vermelha() {
        val zone = Zone(1, "Perigo", ZoneType.DANGER, square)
        val e = OfferEvaluator.evaluate(
            RawOffer(Platform.UBER, 25.0, tripKm = 9.0, tripMin = 15, pickupKm = 1.0, pickupMin = 3),
            profile, cost, listOf(zone), pickup = GeoPoint(0.5, 0.5), dropoff = GeoPoint(5.0, 5.0),
        )
        assertEquals(Signal.RED, e.signal)
        assertEquals(1, e.zoneHits.size)
    }

    @Test fun ponto_no_poligono() {
        assertTrue(Geo.contains(square, GeoPoint(0.5, 0.5)))
        assertFalse(Geo.contains(square, GeoPoint(1.5, 0.5)))
    }
}
