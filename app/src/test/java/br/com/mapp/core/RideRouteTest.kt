package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.notify.NoticeItem
import br.com.mapp.core.notify.RideNotices
import br.com.mapp.core.notify.RidePoints
import br.com.mapp.core.route.RideRoute
import br.com.mapp.core.route.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class RideRouteTest {
    private val cost = VehicleCostConfig(fuelPricePerUnit = 6.0, kmPerUnit = 12.0, maintenancePerKm = 0.1, depreciationPerKm = 0.1)
    private val profile = FilterProfile()
    private val sp: ZoneId = ZoneId.of("America/Sao_Paulo")

    private val pickup = GeoPoint(-23.5614, -46.6559)
    private val stop = GeoPoint(-23.5700, -46.6500)
    private val dropoff = GeoPoint(-23.5874, -46.6576)

    private val direct = RawOffer(
        Platform.UBER, 15.40, tripKm = 5.2, tripMin = 12, pickupKm = 1.6, pickupMin = 4,
        pickupAddress = "Rua das Flores, 123", dropoffAddress = "Av. Paulista, 1000",
    )
    private val withStop = direct.copy(stopAddresses = listOf("Rua do Meio, 50"), stopCount = 1)

    @Test fun direta_tem_A_e_B() {
        val r = RideRoute.of(direct, pickup, emptyList(), dropoff)
        assertEquals(listOf("A", "B"), r.map { it.letter })
        assertEquals(listOf(StopKind.PICKUP, StopKind.DROPOFF), r.map { it.kind })
        assertNull(RideRoute.stopsLabel(direct))
    }

    @Test fun com_parada_A_B_C_e_letras_ficam_mesmo_sem_ponto() {
        val r = RideRoute.of(withStop, null, listOf(stop), dropoff)
        assertEquals(listOf("A", "B", "C"), r.map { it.letter })
        assertEquals(listOf(StopKind.PICKUP, StopKind.STOP, StopKind.DROPOFF), r.map { it.kind })
        assertEquals("Rua do Meio, 50", r[1].address)
        assertNull(r[0].point) // coleta não localizada continua sendo A
        assertEquals(dropoff, r[2].point)
        assertEquals("1 parada", RideRoute.stopsLabel(withStop))
    }

    @Test fun cartao_avisa_paradas_sem_endereco() {
        // "2 paradas" sem endereço: B e C são paradas, D é o destino.
        val raw = direct.copy(stopCount = 2)
        val r = RideRoute.of(raw, pickup, emptyList(), dropoff)
        assertEquals(listOf("A", "B", "C", "D"), r.map { it.letter })
        assertEquals(listOf(null, null), r.filter { it.kind == StopKind.STOP }.map { it.address })
        assertEquals("2 paradas", RideRoute.stopsLabel(raw))
        // Endereços lidos a mais que o número anunciado: vale o maior.
        assertEquals(2, RideRoute.stopCount(direct.copy(stopAddresses = listOf("Rua A, 1", "Rua B, 2"), stopCount = 1)))
    }

    @Test fun letras_depois_do_z_viram_numero() {
        assertEquals("Z", RideRoute.letter(25))
        assertEquals("27", RideRoute.letter(26))
    }

    @Test fun notificacao_com_parada() {
        val offer = OfferEvaluator.evaluate(withStop, profile, cost, pickup = pickup, dropoff = dropoff).copy(stops = listOf(stop))
        val v = RideNotices.values(offer, "UberX", null, 0L, sp)
        assertEquals("Viagem 5,2 km · 12 min · 1 parada", v[NoticeItem.TRIP])
        assertEquals("A · Rua das Flores, 123", v[NoticeItem.PICKUP_ADDRESS])
        assertEquals("B · Rua do Meio, 50", v[NoticeItem.STOPS])
        assertEquals("C · Av. Paulista, 1000", v[NoticeItem.DROPOFF_ADDRESS])
        val n = RideNotices.of(offer, "UberX", null, 0L, sp)
        assertEquals("B · Rua do Meio, 50 · C · Av. Paulista, 1000", n.details.last())
        assertNull(n.mapNote)
    }

    @Test fun notificacao_parada_sem_endereco_e_fora_do_mapa() {
        val raw = direct.copy(stopCount = 1, tripKm = null, tripMin = null)
        val offer = OfferEvaluator.evaluate(raw, profile, cost, pickup = pickup, dropoff = dropoff)
        val v = RideNotices.values(offer, null, null, 0L, sp)
        assertEquals("Viagem com 1 parada", v[NoticeItem.TRIP])
        assertEquals("B · parada", v[NoticeItem.STOPS])
        assertEquals("C · Av. Paulista, 1000", v[NoticeItem.DROPOFF_ADDRESS])
        assertEquals(
            "Parada B não aparece no mapa: endereço não lido",
            RideNotices.mapNote(offer, RidePoints(null, pickup, dropoff)),
        )
        val located = OfferEvaluator.evaluate(withStop, profile, cost, pickup = pickup, dropoff = null)
        assertEquals(
            "Parada B e destino não aparecem no mapa: endereços não localizados",
            RideNotices.mapNote(located, RidePoints(null, pickup, null, listOf(null))),
        )
    }

    @Test fun pontos_incluem_paradas_e_posicao_ancora_na_parada() {
        val far = GeoPoint(-22.9068, -43.1729)
        val p = RideNotices.points(far, null, null, listOf(stop))
        assertNull(p.here) // longe da parada
        assertEquals(listOf(stop), p.all)
        val all = RideNotices.points(null, pickup, dropoff, listOf(stop)).all
        assertEquals(listOf(pickup, stop, dropoff), all)
    }
}
