package br.com.mapp.core

import br.com.mapp.core.engine.OfferSanity
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferSanityTest {

    @Test fun oferta_fantasma_de_15_11_e_rejeitada() {
        // Print de 15/11 00:33: R$ 220,74 · 10,1 km · 17 min, sem busca e sem endereços.
        val raw = RawOffer(Platform.UBER, 220.74, tripKm = 10.1, tripMin = 17)
        assertNotNull(OfferSanity.problem(raw))
    }

    @Test fun valor_impossivel_mesmo_com_busca_e_endereco() {
        val raw = RawOffer(
            Platform.UBER, 220.74, tripKm = 10.1, tripMin = 17, pickupKm = 1.0, pickupMin = 3,
            pickupAddress = "Rua A, 1", dropoffAddress = "Rua B, 2",
        )
        assertFalse(OfferSanity.isPlausible(raw))
    }

    @Test fun sem_busca_e_sem_endereco_e_rejeitada_mesmo_barata() {
        assertFalse(OfferSanity.isPlausible(RawOffer(Platform.UBER, 7.69, tripKm = 3.3, tripMin = 9)))
    }

    @Test fun ofertas_reais_passam() {
        // Do mesmo print: corridas normais.
        assertTrue(OfferSanity.isPlausible(RawOffer(Platform.UBER, 19.13, tripKm = 6.0, tripMin = 12, pickupKm = 1.7, pickupMin = 5)))
        assertTrue(OfferSanity.isPlausible(RawOffer(Platform.UBER, 6.47, tripKm = 3.5, tripMin = 7, pickupAddress = "R. Ada Vaz Cabeda")))
    }

    @Test fun dinamica_alta_passa() {
        // Black curta com dinâmica: R$/km alto, mas R$/h possível.
        assertTrue(OfferSanity.isPlausible(RawOffer(Platform.UBER, 47.0, tripKm = 3.0, tripMin = 8, pickupKm = 2.0, pickupMin = 5)))
        // Corrida longa com dinâmica: R$/h alto, mas R$/km possível.
        assertTrue(OfferSanity.isPlausible(RawOffer(Platform.UBER, 238.0, tripKm = 25.0, tripMin = 30, pickupKm = 2.0, pickupMin = 5)))
    }

    @Test fun so_endereco_sem_trechos_passa() {
        assertTrue(OfferSanity.isPlausible(RawOffer(Platform.NINETY_NINE, 30.0, dropoffAddress = "Av. B, 456")))
    }
}
