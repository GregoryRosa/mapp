package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.OfferMetrics
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
    private val cost = VehicleCostConfig(fuelPricePerUnit = 6.0, kmPerUnit = 12.0, maintenancePerKm = 0.1, depreciationPerKm = 0.1)
    private val profile = FilterProfile(minPerKm = 1.5, goodPerKm = 2.0, minPerHour = 30.0, goodPerHour = 45.0, maxPickupKm = 4.0)
    private val square = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(1.0, 1.0), GeoPoint(1.0, 0.0))

    @Test fun metricas_somam_busca_e_viagem() {
        val m = OfferEvaluator.metrics(RawOffer(Platform.UBER, 20.0, tripKm = 8.0, tripMin = 15, pickupKm = 2.0, pickupMin = 5), cost)
        assertEquals(10.0, m.totalKm!!, 1e-9)
        assertEquals(2.0, m.perKm!!, 1e-9)
        assertEquals(60.0, m.perHour!!, 1e-9)
        assertEquals(20.0 - 10.0 * 0.7, m.netProfit!!, 1e-9)
    }

    @Test fun tempo_da_viagem_ilegivel_e_estimado_pela_velocidade_da_busca() {
        // Corrida real de 13/11 07:03: o leitor pegou 3,4 km da viagem sem o tempo. Antes o tempo
        // contava como zero e o R$/h dava 10,08 ÷ 4 min = R$ 151,20/h.
        val raw = RawOffer(Platform.UBER, 10.08, tripKm = 3.4, tripMin = null, pickupKm = 1.3, pickupMin = 4)
        val legs = OfferEvaluator.legs(raw)
        assertTrue(legs.timeEstimated)
        assertFalse(legs.kmEstimated)
        // Busca a 19,5 km/h → viagem 3,4 km ≈ 10,5 min.
        assertEquals(4 + 3.4 / 19.5 * 60, legs.minutes!!, 1e-9)
        val m = OfferEvaluator.metrics(raw, cost)
        assertEquals(14, m.totalMin)
        assertEquals(4.7, m.totalKm!!, 1e-9)
        assertEquals(10.08 / (4 + 3.4 / 19.5 * 60) * 60, m.perHour!!, 1e-9)
        assertTrue(m.perHour!! in 40.0..44.0)
    }

    @Test fun km_ilegivel_e_estimado_e_velocidade_fica_em_faixa_real() {
        // Busca só com tempo: km sai da velocidade da viagem (20,4 km/h).
        val m = OfferEvaluator.metrics(RawOffer(Platform.UBER, 12.0, tripKm = 3.4, tripMin = 10, pickupMin = 4), cost)
        assertEquals(3.4 + 4 * 20.4 / 60, m.totalKm!!, 1e-9)
        // Sem trecho completo: 25 km/h. Velocidade absurda (OCR) fica entre 15 e 40 km/h.
        assertEquals(6 * 60 / 25.0, OfferEvaluator.legs(RawOffer(Platform.UBER, 10.0, tripKm = 6.0)).minutes!!, 1e-9)
        val rapida = RawOffer(Platform.UBER, 10.0, tripKm = 5.0, pickupKm = 9.0, pickupMin = 1)
        assertEquals(1 + 5.0 / 40.0 * 60, OfferEvaluator.legs(rapida).minutes!!, 1e-9)
        // Trechos completos: nada estimado.
        val completa = OfferEvaluator.legs(RawOffer(Platform.UBER, 20.0, tripKm = 8.0, tripMin = 15, pickupKm = 2.0, pickupMin = 5))
        assertFalse(completa.timeEstimated || completa.kmEstimated)
    }

    @Test fun historico_gravado_com_tempo_faltando_e_corrigido() {
        val raw = RawOffer(Platform.UBER, 10.08, tripKm = 3.4, pickupKm = 1.3, pickupMin = 4)
        val gravado = OfferMetrics(4.7, 4, 10.08 / 4.7, 151.20, 6.07, 6.07 / 4.7)
        val r = OfferEvaluator.repaired(raw, gravado)
        assertEquals(14, r.totalMin)
        assertTrue(r.perHour!! in 40.0..44.0)
        assertEquals(6.07, r.netProfit!!, 1e-9) // km não mudou: lucro igual
        // Oferta completa: devolve o que estava gravado.
        val ok = OfferMetrics(10.0, 20, 2.0, 60.0, 13.0, 1.3)
        assertEquals(ok, OfferEvaluator.repaired(RawOffer(Platform.UBER, 20.0, tripKm = 8.0, tripMin = 15, pickupKm = 2.0, pickupMin = 5), ok))
        // Km estimado: lucro refeito com o custo/km da época ((10 − 6) ÷ 4 = R$ 1/km).
        val semKm = RawOffer(Platform.UBER, 10.0, tripKm = 4.0, tripMin = 12, pickupMin = 3)
        val r2 = OfferEvaluator.repaired(semKm, OfferMetrics(4.0, 15, 2.5, 40.0, 6.0, 1.5))
        assertEquals(10.0 - (4.0 + 1.0) * 1.0, r2.netProfit!!, 1e-9)
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
