package br.com.mapp.core

import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.shift.Fix
import br.com.mapp.core.shift.Odometer
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftCodec
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ShiftTest {
    private val eps = 1e-6
    private val h = 3_600_000L

    private fun trip(cat: String?, price: Double, pickup: Double?, km: Double?) =
        ShiftTrip(0, Platform.UBER, cat, price, pickup, km)

    @Test fun marcacao_manual_coloca_e_tira_a_corrida_sem_duplicar() {
        val offerAt = 10 * h
        val auto = ShiftTrip(offerAt + 40_000, Platform.UBER, "UberX", 14.06, 1.0, 2.9) // contada sozinha
        val s = Shift(startedAt = 8 * h, trips = listOf(auto))
        val manual = ShiftTrip(offerAt, Platform.UBER, null, 14.06, 1.0, 2.9)
        assertEquals(s, s.withTrip(manual, offerAt, present = true)) // já estava: não duplica
        val other = ShiftTrip(11 * h, Platform.UBER, null, 7.69, 0.5, 1.4)
        val added = s.withTrip(other, 11 * h, present = true)
        assertEquals(2, added.trips.size)
        assertEquals(7.69, added.trips.last().price, eps)
        val removed = added.withTrip(manual, offerAt, present = false)
        assertEquals(listOf(other), removed.trips)
        // Mesmo valor, mas muito depois: é outra corrida.
        assertEquals(-1, s.tripIndexFor(Platform.UBER, 14.06, offerAt + 2 * h))
    }

    @Test fun jornada_dona_da_oferta() {
        val cur = Shift(startedAt = 20 * h)
        val ontem = Shift(startedAt = 0)
        val hoje = Shift(startedAt = 10 * h)
        val history = listOf(hoje, ontem)
        assertTrue(ShiftMath.ownerOf(21 * h, cur, history) === cur)
        assertTrue(ShiftMath.ownerOf(12 * h, cur, history) === hoje)
        assertTrue(ShiftMath.ownerOf(5 * h, null, history) === ontem)
        assertNull(ShiftMath.ownerOf(-1, cur, history))
    }

    @Test fun resumo_da_jornada() {
        val shift = Shift(startedAt = 0, gpsKm = 50.0, trips = listOf(
            trip("UberX", 20.0, 2.0, 10.0),
            trip("UberX", 15.0, 1.0, 6.0),
            trip("Comfort", 25.0, 3.0, 8.0),
            trip(null, 10.0, null, null),
        ))
        val s = ShiftMath.summary(shift, costPerKm = 0.5, now = 2 * h)
        assertEquals(2.0, s.hours, eps)
        assertEquals(4, s.trips)
        assertEquals(70.0, s.revenue, eps)
        assertEquals(50.0, s.totalKm, eps)
        assertEquals(24.0, s.kmWithTrip, eps)
        assertEquals(26.0, s.kmWithoutTrip, eps)
        assertEquals(25.0, s.cost, eps)
        assertEquals(45.0, s.profit, eps)
        assertEquals(1.4, s.perKm!!, eps)
        assertEquals(35.0, s.perHour!!, eps)
        assertEquals(8.0, s.avgTripKm!!, eps)
        assertEquals(listOf("UberX", "Comfort", ShiftMath.NO_CATEGORY), s.categories.map { it.label })
        assertEquals(50.0, s.categories[0].percent, eps)
        assertEquals(25.0, s.categories[1].percent, eps)
    }

    @Test fun gps_menor_que_as_corridas_usa_as_corridas() {
        val shift = Shift(startedAt = 0, gpsKm = 3.0, trips = listOf(trip("UberX", 20.0, 2.0, 10.0)))
        val s = ShiftMath.summary(shift, 0.0, h)
        assertEquals(12.0, s.totalKm, eps)
        assertEquals(2.0, s.kmWithoutTrip, eps)
    }

    @Test fun jornada_sem_corridas_nao_divide_por_zero() {
        val s = ShiftMath.summary(Shift(startedAt = 0), 0.6, 0)
        assertNull(s.perKm)
        assertNull(s.perHour)
        assertNull(s.avgTripKm)
        assertTrue(s.categories.isEmpty())
        assertTrue(ShiftMath.lines(s).isNotEmpty())
    }

    @Test fun pausa_nao_conta_tempo() {
        val s = Shift(startedAt = 0).pause(h).resume(3 * h)
        assertEquals(2 * h, s.elapsedMs(4 * h))
        assertTrue(s.pause(5 * h).paused)
        assertEquals(3 * h, s.pause(5 * h).elapsedMs(9 * h))
    }

    @Test fun codec_ida_e_volta() {
        val shift = Shift(startedAt = 10, activeMs = 20, resumedAt = null, gpsKm = 12.5, trips = listOf(
            ShiftTrip(30, Platform.NINETY_NINE, "Pop", 14.9, 0.9, 9.4),
            ShiftTrip(40, Platform.UBER, null, 20.0, null, null),
        ))
        assertEquals(shift, ShiftCodec.decode(ShiftCodec.encode(shift)))
        assertEquals(Shift(startedAt = 5), ShiftCodec.decode(ShiftCodec.encode(Shift(startedAt = 5))))
        assertNull(ShiftCodec.decode("lixo"))
        assertNull(ShiftCodec.decode(null))
    }

    @Test fun hodometro_ignora_tremido_impreciso_e_salto() {
        val o = Odometer()
        val base = GeoPoint(-30.0, -51.0)
        fun at(dLatM: Double, sec: Long, acc: Float? = 5f) =
            Fix(GeoPoint(base.lat + dLatM / 111_195.0, base.lon), acc, sec * 1000)

        assertEquals(0.0, o.add(at(0.0, 0)), eps)          // âncora
        assertEquals(0.0, o.add(at(10.0, 5)), eps)         // tremido parado
        assertEquals(0.0, o.add(at(500.0, 10, 200f)), eps) // impreciso
        assertEquals(0.1, o.add(at(100.0, 15)), 1e-3)      // andou 100 m
        assertEquals(0.0, o.add(at(20_100.0, 20)), eps)    // 20 km em 5 s: salto
        assertEquals(0.2, o.add(at(20_300.0, 30)), 1e-3)   // segue da nova âncora
    }

    @Test fun jornada_do_dia() {
        val zone = ZoneId.of("America/Sao_Paulo")
        fun ms(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        val now = ms(12, 20)
        val ontem = Shift(startedAt = ms(11, 23))
        val hoje = Shift(startedAt = ms(12, 6))
        assertEquals(ontem, ShiftMath.ofToday(ontem, hoje, now, zone))   // aberta vale mesmo se começou ontem
        assertEquals(hoje, ShiftMath.ofToday(null, hoje, now, zone))     // encerrada hoje
        assertNull(ShiftMath.ofToday(null, ontem, now, zone))            // encerrada ontem: nada
        assertNull(ShiftMath.ofToday(null, null, now, zone))
    }

    // ------------------------------------------------------------------ histórico de jornadas

    @Test fun custo_do_dia_fica_guardado_na_jornada() {
        val shift = Shift(startedAt = 0, gpsKm = 10.0, trips = listOf(trip("UberX", 30.0, 1.0, 5.0)), costPerKm = 1.0)
        assertEquals(10.0, ShiftMath.summary(shift, costPerKm = 5.0, now = h).cost, eps)
        assertEquals(50.0, ShiftMath.summary(shift.copy(costPerKm = null), costPerKm = 5.0, now = h).cost, eps)
        assertEquals(shift, ShiftCodec.decode(ShiftCodec.encode(shift)))
    }

    @Test fun lista_de_jornadas_ida_e_volta() {
        val a = Shift(startedAt = 1, resumedAt = null, gpsKm = 3.0, trips = listOf(trip("Pop", 10.0, null, 4.0)))
        val b = Shift(startedAt = 2, resumedAt = null, costPerKm = 0.8)
        assertEquals(listOf(a, b), ShiftCodec.decodeList(ShiftCodec.encodeList(listOf(a, b))))
        assertEquals(emptyList<Shift>(), ShiftCodec.decodeList(null))
        // Formato antigo (sem custo/km no cabeçalho) continua legível.
        assertEquals(Shift(startedAt = 7, resumedAt = null), ShiftCodec.decode("v1\t7\t0\t\t0.0"))
    }

    @Test fun soma_de_varias_jornadas() {
        val d1 = ShiftMath.summary(
            Shift(startedAt = 0, activeMs = h, resumedAt = null, gpsKm = 20.0,
                trips = listOf(trip("UberX", 20.0, 1.0, 8.0), trip("Comfort", 20.0, 1.0, 4.0))),
            costPerKm = 0.5, now = 0,
        )
        val d2 = ShiftMath.summary(
            Shift(startedAt = 0, activeMs = h, resumedAt = null, gpsKm = 30.0, trips = listOf(trip("UberX", 20.0, 2.0, 6.0))),
            costPerKm = 0.5, now = 0,
        )
        val t = ShiftMath.combine(listOf(d1, d2))
        assertEquals(2.0, t.hours, eps)
        assertEquals(3, t.trips)
        assertEquals(60.0, t.revenue, eps)
        assertEquals(50.0, t.totalKm, eps)
        assertEquals(25.0, t.cost, eps)
        assertEquals(35.0, t.profit, eps)
        assertEquals(1.2, t.perKm!!, eps)
        assertEquals(30.0, t.perHour!!, eps)
        assertEquals(6.0, t.avgTripKm!!, eps)
        assertEquals("UberX", t.categories[0].label)
        assertEquals(2.0 * 100 / 3, t.categories[0].percent, eps)
    }
}
