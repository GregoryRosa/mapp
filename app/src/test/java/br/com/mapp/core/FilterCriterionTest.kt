package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.filter.FilterCriterion
import br.com.mapp.core.filter.FilterCriterion.PER_HOUR
import br.com.mapp.core.filter.FilterCriterion.PER_KM
import br.com.mapp.core.filter.FilterCriterion.PICKUP_KM
import br.com.mapp.core.filter.FilterCriterion.PICKUP_MIN
import br.com.mapp.core.filter.FilterCriterion.PRICE
import br.com.mapp.core.filter.FilterCriterion.RATING
import br.com.mapp.core.filter.ThresholdRange
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCriterionTest {

    private val faixas = mapOf(
        PER_KM to ThresholdRange(1.60, 2.20),
        PER_HOUR to ThresholdRange(32.0, 50.0),
        RATING to ThresholdRange(4.75, 4.92),
        PRICE to ThresholdRange(9.0, 14.5),
        PICKUP_KM to ThresholdRange(1.5, 3.5),
        PICKUP_MIN to ThresholdRange(4.0, 9.0),
    )

    @Test fun faixa_vai_e_volta_nos_seis_criterios() {
        assertEquals(FilterCriterion.entries.toSet(), faixas.keys)
        faixas.forEach { (c, r) -> assertEquals(c.name, r, c.range(c.apply(FilterProfile(), r))) }
        // Todos no mesmo perfil: um critério não mexe no outro.
        val todos = faixas.entries.fold(FilterProfile()) { p, (c, r) -> c.apply(p, r) }
        faixas.forEach { (c, r) -> assertEquals(c.name, r, c.range(todos)) }
    }

    @Test fun cada_criterio_grava_nos_seus_campos() {
        val p = FilterProfile()
        PER_KM.apply(p, ThresholdRange(1.6, 2.2)).let {
            assertEquals(1.6, it.minPerKm!!, 1e-9)
            assertEquals(2.2, it.goodPerKm!!, 1e-9)
        }
        PER_HOUR.apply(p, ThresholdRange(32.0, 50.0)).let {
            assertEquals(32.0, it.minPerHour!!, 1e-9)
            assertEquals(50.0, it.goodPerHour!!, 1e-9)
        }
        RATING.apply(p, ThresholdRange(4.7, 4.9)).let {
            assertEquals(4.7, it.minRating!!, 1e-9)
            assertEquals(4.9, it.goodRating!!, 1e-9)
        }
        PRICE.apply(p, ThresholdRange(8.0, 12.0)).let {
            assertEquals(8.0, it.minPrice!!, 1e-9)
            assertEquals(12.0, it.goodPrice!!, 1e-9)
        }
        // Menos é melhor: low = verde até, high = amarelo até (máximo).
        PICKUP_KM.apply(p, ThresholdRange(2.0, 4.5)).let {
            assertEquals(2.0, it.goodPickupKm!!, 1e-9)
            assertEquals(4.5, it.maxPickupKm!!, 1e-9)
        }
        PICKUP_MIN.apply(p, ThresholdRange(4.6, 8.4)).let {
            assertEquals(5, it.goodPickupMin) // arredonda
            assertEquals(8, it.maxPickupMin)
        }
    }

    @Test fun sem_faixa_desliga_o_criterio() {
        var p = FilterProfile(
            minPrice = 8.0, goodPrice = 12.0, goodPickupKm = 2.0,
            maxPickupMin = 8, goodPickupMin = 5, minRating = 4.7, goodRating = 4.9,
        )
        FilterCriterion.entries.forEach { c ->
            assertNotNull(c.name, c.range(p))
            p = c.apply(p, null)
            assertNull(c.name, c.range(p))
        }
        // Os dois campos de cada critério ficam null; o resto do perfil não muda.
        assertEquals(
            FilterProfile(minPerKm = null, goodPerKm = null, minPerHour = null, goodPerHour = null, maxPickupKm = null),
            p,
        )
    }

    @Test fun perfil_antigo_vira_faixa_sem_amarelo_e_mesmo_semaforo() {
        val antigo = FilterProfile(minPrice = 8.0, maxPickupMin = 10, minRating = 4.7)
        assertEquals(ThresholdRange(1.5, 2.0), PER_KM.range(antigo))
        assertEquals(ThresholdRange(8.0, 8.0), PRICE.range(antigo))
        assertEquals(ThresholdRange(4.7, 4.7), RATING.range(antigo))
        assertEquals(ThresholdRange(4.0, 4.0), PICKUP_KM.range(antigo))
        assertEquals(ThresholdRange(10.0, 10.0), PICKUP_MIN.range(antigo))
        // Salvar de novo o que os sliders leram não muda a cor de nenhuma oferta.
        val salvo = FilterCriterion.entries.fold(antigo) { p, c -> c.apply(p, c.range(p)) }
        val cost = VehicleCostConfig()
        val ofertas = listOf(
            RawOffer(Platform.UBER, 25.0, tripKm = 9.0, tripMin = 15, pickupKm = 1.0, pickupMin = 3, passengerRating = 4.75),
            RawOffer(Platform.UBER, 17.0, tripKm = 9.0, tripMin = 20, pickupKm = 1.0, pickupMin = 3, passengerRating = 4.8),
            RawOffer(Platform.UBER, 30.0, tripKm = 10.0, tripMin = 15, pickupKm = 4.0, pickupMin = 10, passengerRating = 4.7),
            RawOffer(Platform.UBER, 30.0, tripKm = 10.0, tripMin = 15, pickupKm = 4.1, pickupMin = 3),
            RawOffer(Platform.UBER, 30.0, tripKm = 10.0, tripMin = 15, pickupKm = 1.0, pickupMin = 11),
            RawOffer(Platform.UBER, 7.5, tripKm = 2.0, tripMin = 5),
            RawOffer(Platform.UBER, 30.0, tripKm = 10.0, tripMin = 15, passengerRating = 4.6),
        )
        ofertas.forEach { raw ->
            assertEquals(
                raw.toString(),
                OfferEvaluator.evaluate(raw, antigo, cost).signal,
                OfferEvaluator.evaluate(raw, salvo, cost).signal,
            )
        }
    }

    @Test fun faixa_de_quanto_menos_melhor() {
        assertNull(ThresholdRange.ofMax(null, null))
        assertEquals(ThresholdRange(4.0, 4.0), ThresholdRange.ofMax(null, 4.0))
        assertEquals(ThresholdRange(2.0, 2.0), ThresholdRange.ofMax(2.0, null))
        assertEquals(ThresholdRange(2.0, 4.0), ThresholdRange.ofMax(2.0, 4.0))
        assertEquals(ThresholdRange(4.0, 4.0), ThresholdRange.ofMax(5.0, 4.0)) // verde nunca passa do máximo
    }

    @Test fun sentido_inteiros_e_sugestao() {
        assertTrue(PER_KM.higherIsBetter && PER_HOUR.higherIsBetter && RATING.higherIsBetter && PRICE.higherIsBetter)
        assertFalse(PICKUP_KM.higherIsBetter)
        assertFalse(PICKUP_MIN.higherIsBetter)
        assertTrue(PICKUP_MIN.integer)
        assertEquals(listOf(PICKUP_MIN), FilterCriterion.entries.filter { it.integer })
        // Sugestão cabe na escala do slider e o amarelo não passa do verde.
        FilterCriterion.entries.forEach { c ->
            assertTrue(c.name, c.default.low <= c.default.high)
            assertTrue(c.name, c.default.low >= c.scale.min && c.default.high <= c.scale.max)
        }
    }

    @Test fun formatos() {
        assertEquals("R$ 1,60", PER_KM.format(1.6))
        assertEquals("R$ 40", PER_HOUR.format(40.0))
        assertEquals("R$ 100", PER_HOUR.format(100.0))
        assertEquals("R$ 42,5", PER_HOUR.format(42.5))
        assertEquals("4,71", RATING.format(4.71))
        assertEquals("R$ 8,00", PRICE.format(8.0))
        assertEquals("4,0 km", PICKUP_KM.format(4.0))
        assertEquals("8 min", PICKUP_MIN.format(8.0))
    }
}
