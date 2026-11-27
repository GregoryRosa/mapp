package br.com.mapp.core

import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.trip.ManualTrip
import br.com.mapp.core.trip.TripCapture
import br.com.mapp.core.trip.TripDetails
import br.com.mapp.core.trip.TripDetailsScreen
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetailsTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val pickup = "Rua Coronel Bordini, Mont'Serrat - Porto Alegre - RS, 90440-003, Brasil"
    private val dropoff = "R. Ramiro Barcelos, Moinhos de Vento - Porto Alegre - RS, 90035-001, Brasil"

    // Tela "Detalhes da viagem" da Uber (print de 27/11/2026) como chega pela Acessibilidade.
    private val tree = listOf(
        "Detalhes da viagem", "Electric • 27 de nov. de 2026 • 6:35", "R$ 6,47", "Preço antecipado: R$ 6,47",
        "Duração", "4 min 49 segundos", "Distância", "1.95 km", pickup, dropoff,
        "Pontos ganhos: 1 ponto", "Seus ganhos",
    )

    // A mesma tela pelo OCR: nomes do mapa, rótulos na mesma linha e endereço quebrado em duas.
    private val ocr = listOf(
        "Detalhes da viagem", "Electric • 27 de nov. de 2026 • 6:35", "R$ 6,47", "Preço antecipado: R$ 6,47",
        "Hospital Moinhos", "Vento", "Parque Moinhos", "de Vento", "Banco de Leite",
        "Duração Distância", "4 min 49 segundos 1.95 km",
        "Rua Coronel Bordini, Mont'Serrat -", "Porto Alegre - RS, 90440-003, Brasil",
        "R. Ramiro Barcelos, Moinhos de Vento -", "Porto Alegre - RS, 90035-001, Brasil",
        "Pontos ganhos: 1 ponto", "Seus ganhos",
    )

    @After fun reset() {
        TripCapture.cancel()
        TripCapture.consume()
    }

    @Test fun le_a_tela_pelo_texto() {
        val d = TripDetailsScreen.read(Platform.UBER, tree, zone = zone)!!
        assertEquals(6.47, d.price!!, 1e-9)
        assertEquals("Electric", d.category)
        assertEquals(at(2026, 11, 27, 6, 35), d.atMillis)
        assertEquals(1.95, d.km!!, 1e-9)
        assertEquals(289, d.seconds)
        assertEquals(5, d.minutes)
        assertEquals(pickup, d.pickup)
        assertEquals(dropoff, d.dropoff)
        assertTrue(d.stops.isEmpty())
        assertTrue(d.complete)
    }

    @Test fun le_a_tela_pela_imagem() {
        val d = TripDetailsScreen.read(Platform.UBER, ocr, fromOcr = true, zone = zone)!!
        assertEquals(6.47, d.price!!, 1e-9)
        assertEquals(1.95, d.km!!, 1e-9)
        assertEquals(289, d.seconds)
        assertEquals(pickup, d.pickup)
        assertEquals(dropoff, d.dropoff)
        assertTrue(d.complete)
    }

    @Test fun outras_telas_e_outros_apps_nao_contam() {
        assertNull(TripDetailsScreen.read(Platform.UBER, listOf("Página inicial", "R$ 0,00", "HOJE"), zone = zone))
        assertNull(TripDetailsScreen.read(Platform.NINETY_NINE, tree, zone = zone))
        // Tela certa, mas o valor ainda não carregou.
        assertNull(TripDetailsScreen.read(Platform.UBER, listOf("Detalhes da viagem", "Duração"), zone = zone))
    }

    @Test fun formatos_variados() {
        val d = TripDetailsScreen.read(
            Platform.UBER,
            listOf(
                "Detalhes da viagem", "UberX · 3 de dez. de 2026 · 11:05 PM", "R$ 1.234,50",
                "1 h 12 min", "38,4 km", "Av. Ipiranga, 1000, Porto Alegre", "Rua X, 10, Canoas",
                "Rua Y, 20, Esteio",
            ),
            zone = zone,
        )!!
        assertEquals(1234.5, d.price!!, 1e-9)
        assertEquals("UberX", d.category)
        assertEquals(at(2026, 12, 3, 23, 5), d.atMillis)
        assertEquals(38.4, d.km!!, 1e-9)
        assertEquals(72 * 60, d.seconds)
        assertEquals("Av. Ipiranga, 1000, Porto Alegre", d.pickup)
        assertEquals(listOf("Rua X, 10, Canoas"), d.stops)
        assertEquals("Rua Y, 20, Esteio", d.dropoff)
    }

    @Test fun captura_so_armada_e_completa_na_hora() {
        val d = TripDetailsScreen.read(Platform.UBER, tree, zone = zone)!!
        assertFalse(TripCapture.offer(d, 1_000))
        assertNull(TripCapture.result.value)

        TripCapture.arm(1_000)
        assertTrue(TripCapture.isArmed(2_000))
        assertTrue(TripCapture.offer(d, 2_000))
        assertFalse(TripCapture.isArmed(2_001))
        assertEquals(d, TripCapture.consume())
        assertNull(TripCapture.result.value)
    }

    @Test fun captura_incompleta_espera_e_fica_com_a_melhor() {
        TripCapture.arm(0)
        val partial = TripDetails(price = 6.47, atMillis = 5L)
        val better = partial.copy(km = 1.95, pickup = "Rua A, 1, POA")
        assertFalse(TripCapture.offer(partial, 100))
        assertFalse(TripCapture.offer(better, 1_000))
        assertTrue(TripCapture.offer(partial, 100 + TripCapture.PARTIAL_WAIT_MS))
        assertEquals(better, TripCapture.consume())
    }

    @Test fun captura_vence() {
        TripCapture.arm(0)
        val d = TripDetailsScreen.read(Platform.UBER, tree, zone = zone)!!
        assertFalse(TripCapture.offer(d, TripCapture.ARM_MS + 1))
        TripCapture.arm(0)
        TripCapture.cancel()
        assertFalse(TripCapture.offer(d, 10))
    }

    private fun record(id: Long, price: Double, at: Long, platform: Platform = Platform.UBER, accepted: Boolean? = null) =
        OfferRecord(
            id,
            EvaluatedOffer(
                RawOffer(platform, price, capturedAt = at),
                OfferMetrics(null, null, null, null, null, null),
                Signal.GREEN,
                emptyList(),
            ),
            accepted,
        )

    @Test fun acha_a_oferta_da_mesma_viagem() {
        val t = at(2026, 11, 27, 6, 35)
        val d = TripDetails(price = 6.47, atMillis = t)
        val offers = listOf(
            record(1, 6.47, t - 40 * 60_000), // longe demais
            record(2, 6.47, t - 3 * 60_000),
            record(3, 6.47, t - 60_000, platform = Platform.NINETY_NINE),
            record(4, 7.10, t - 30_000),
            record(5, 6.47, t + 10 * 60_000),
        )
        assertEquals(2L, ManualTrip.match(d, offers)?.id)
        assertNull(ManualTrip.match(d.copy(price = 9.0), offers))
        assertNull(ManualTrip.match(d.copy(atMillis = null), offers))
    }

    @Test fun oferta_e_horario_digitado() {
        val t = ManualTrip.parseDateTime("27/11/2026 06:35", zone)!!
        assertEquals(at(2026, 11, 27, 6, 35), t)
        assertEquals("27/11/2026 06:35", ManualTrip.formatDateTime(t, zone))
        assertNull(ManualTrip.parseDateTime("31/02/2026 06:35", zone))
        assertNull(ManualTrip.parseDateTime("ontem", zone))

        val d = TripDetailsScreen.read(Platform.UBER, tree, zone = zone)!!
        val raw = ManualTrip.offer(d, t)
        assertEquals(6.47, raw.price, 1e-9)
        assertEquals(1.95, raw.tripKm!!, 1e-9)
        assertEquals(5, raw.tripMin)
        assertNull(raw.pickupKm)
        assertEquals("Electric", raw.category)
        assertEquals(pickup, raw.pickupAddress)
        assertEquals(t, raw.capturedAt)
        assertEquals(ManualTrip.TAG, raw.rawText)
        assertNotNull(raw.dropoffAddress)
    }
}
