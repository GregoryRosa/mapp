package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.notify.RideNotices
import br.com.mapp.core.notify.RidePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RideNoticeTest {
    private val cost = VehicleCostConfig(fuelPricePerUnit = 6.0, kmPerUnit = 12.0, maintenancePerKm = 0.1, depreciationPerKm = 0.1)
    private val profile = FilterProfile(minPerKm = 1.5, goodPerKm = 2.0, minPerHour = 30.0, goodPerHour = 45.0, maxPickupKm = 4.0)
    private val sp: ZoneId = ZoneId.of("America/Sao_Paulo")
    /** 14/11/2026 14:16 em São Paulo. */
    private val acceptedAt = ZonedDateTime.of(2026, 11, 14, 14, 16, 0, 0, sp).toInstant().toEpochMilli()

    private val pickup = GeoPoint(-23.5614, -46.6559)
    private val dropoff = GeoPoint(-23.5874, -46.6576)
    private val here = GeoPoint(-23.5540, -46.6620)

    private val raw = RawOffer(
        Platform.UBER, 15.40,
        tripKm = 5.2, tripMin = 12, pickupKm = 1.6, pickupMin = 4,
        pickupAddress = "Rua das Flores, 123", dropoffAddress = "Av. Paulista, 1000",
        passengerRating = 4.92, category = "UberX",
    )

    private fun around(p: GeoPoint, d: Double = 0.005) = listOf(
        GeoPoint(p.lat - d, p.lon - d), GeoPoint(p.lat - d, p.lon + d),
        GeoPoint(p.lat + d, p.lon + d), GeoPoint(p.lat + d, p.lon - d),
    )

    @Test fun fechada_mostra_tipo_valor_e_busca_viagem() {
        val offer = OfferEvaluator.evaluate(raw, profile, cost, pickup = pickup, dropoff = dropoff)
        val n = RideNotices.of(offer, "UberX", "caio", acceptedAt, sp)
        assertEquals("🟢 Corrida aceita · UberX · R$ 15,40", n.title)
        assertEquals("Busca 1,6 km · 4 min → Viagem 5,2 km · 12 min", n.summary)
    }

    @Test fun expandida_tem_numeros_chegada_enderecos_e_passageiro() {
        val offer = OfferEvaluator.evaluate(raw, profile, cost, pickup = pickup, dropoff = dropoff)
        val n = RideNotices.of(offer, "UberX", "caio", acceptedAt, sp, points = RidePoints(here, pickup, dropoff))
        assertEquals(
            listOf(
                // 15,40 ÷ 6,8 km; 15,40 ÷ 16 min × 60; 15,40 − 6,8 × 0,70
                "R$ 2,26/km · R$ 57,75/h · Lucro R$ 10,64",
                "Total 6,8 km · 16 min · Chegada ~14:32",
                "A · Rua das Flores, 123",
                "B · Av. Paulista, 1000",
                "Passageiro: Caio · ★ 4,92",
            ),
            n.details,
        )
        assertNull(n.mapNote)
    }

    @Test fun aceite_visto_so_no_embarque_conta_so_a_viagem_na_chegada() {
        val offer = OfferEvaluator.evaluate(raw, profile, cost)
        val n = RideNotices.of(offer, "UberX", null, acceptedAt, sp, onBoard = true)
        assertTrue(n.details.contains("Total 6,8 km · 16 min · Chegada ~14:28"))
    }

    @Test fun zona_perigosa_vem_antes_e_o_que_nao_cabe_sai_do_fim() {
        val centro = Zone(name = "Centro", type = ZoneType.DANGER, polygon = around(dropoff))
        val offer = OfferEvaluator.evaluate(raw, profile, cost, listOf(centro), pickup, dropoff)
        val n = RideNotices.of(offer, "UberX", "caio", acceptedAt, sp)
        assertTrue(n.title.startsWith("🔴 "))
        assertEquals(RideNotices.MAX_DETAILS, n.details.size)
        assertEquals("⚠ Destino: Zona perigosa (Centro)", n.details[2])
        assertFalse(n.details.any { it.startsWith("Passageiro") })
    }

    @Test fun numero_estimado_ganha_til() {
        // Tempo da viagem não lido: R$/h e minutos vêm da velocidade da busca.
        val offer = OfferEvaluator.evaluate(raw.copy(tripMin = null), profile, cost)
        val n = RideNotices.of(offer, null, null, acceptedAt, sp)
        assertEquals("Busca 1,6 km · 4 min → Viagem 5,2 km", n.summary)
        assertTrue(n.details[0], n.details[0].startsWith("R$ 2,26/km · ~R$ "))
        assertTrue(n.details[1], n.details[1].startsWith("Total 6,8 km · ~"))
    }

    @Test fun nome_do_tipo_por_app() {
        val pop = OfferEvaluator.evaluate(RawOffer(Platform.NINETY_NINE, 12.0, tripKm = 4.0, tripMin = 10), profile, cost)
        assertTrue(RideNotices.of(pop, "Pop", null, acceptedAt, sp).title.endsWith("Corrida aceita · 99 Pop · R$ 12,00"))
        assertTrue(RideNotices.of(pop, null, null, acceptedAt, sp).title.endsWith("Corrida aceita · 99 · R$ 12,00"))
        val uber = OfferEvaluator.evaluate(RawOffer(Platform.UBER, 12.0, tripKm = 4.0, tripMin = 10), profile, cost)
        assertTrue(RideNotices.of(uber, null, null, acceptedAt, sp).title.endsWith("Corrida aceita · Uber · R$ 12,00"))
    }

    @Test fun sem_km_nem_tempo() {
        val offer = OfferEvaluator.evaluate(RawOffer(Platform.UBER, 9.0), profile, cost)
        val n = RideNotices.of(offer, null, null, acceptedAt, sp)
        assertEquals("Distância e tempo não lidos", n.summary)
    }

    @Test fun aviso_do_mapa_quando_coleta_ou_destino_ficam_de_fora() {
        val semEndereco = OfferEvaluator.evaluate(raw.copy(pickupAddress = null, dropoffAddress = null), profile, cost)
        assertEquals("Sem mapa: endereços da corrida não lidos", RideNotices.mapNote(semEndereco, RidePoints(null, null, null)))
        assertEquals(
            "Coleta e destino não aparecem no mapa: endereços não lidos",
            RideNotices.mapNote(semEndereco, RidePoints(here, null, null)),
        )
        val lido = OfferEvaluator.evaluate(raw, profile, cost)
        assertEquals(
            "Destino não aparece no mapa: endereço não localizado",
            RideNotices.mapNote(lido, RidePoints(here, pickup, null)),
        )
        assertNull(RideNotices.mapNote(lido, RidePoints(null, pickup, dropoff)))
    }

    @Test fun posicao_velha_longe_da_coleta_fica_fora_do_mapa() {
        val rio = GeoPoint(-22.9068, -43.1729)
        assertNull(RideNotices.points(rio, pickup, dropoff).here)
        assertEquals(here, RideNotices.points(here, pickup, dropoff).here)
        // Sem coleta nem destino: a posição é tudo o que há.
        assertEquals(rio, RideNotices.points(rio, null, null).here)
    }

    @Test fun retangulo_com_folga_e_lado_minimo() {
        val f = RideNotices.frame(listOf(here, pickup, dropoff))
        assertNotNull(f)
        f!!
        listOf(here, pickup, dropoff).forEach {
            assertTrue(it.lat < f.north && it.lat > f.south && it.lon < f.east && it.lon > f.west)
        }
        // Folga de 20% de cada lado na latitude (o lado maior).
        val latSpan = here.lat - dropoff.lat
        assertEquals(latSpan * 1.4, f.north - f.south, 1e-9)

        val one = RideNotices.frame(listOf(pickup))!!
        assertEquals(RideNotices.MIN_SPAN_DEG, one.north - one.south, 1e-12)
        assertEquals(RideNotices.MIN_SPAN_DEG, one.east - one.west, 1e-12)
        assertEquals(pickup.lat, one.centerLat, 1e-12)
        assertEquals(pickup.lon, one.centerLon, 1e-12)

        assertNull(RideNotices.frame(emptyList()))
    }
}
