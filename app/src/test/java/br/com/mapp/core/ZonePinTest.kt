package br.com.mapp.core

import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.pin.ZonePin
import br.com.mapp.core.pin.ZonePinCodec
import br.com.mapp.core.pin.ZonePins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonePinTest {
    private val sp = GeoPoint(-23.5505, -46.6333)

    @Test fun codec_ida_e_volta() {
        val pins = listOf(
            ZonePin(1L, ZoneType.DANGER, sp),
            ZonePin(2L, ZoneType.HIGH_DEMAND, GeoPoint(-22.9, -43.2)),
            ZonePin(3L, ZoneType.CUSTOM, GeoPoint(0.0, 0.0)),
        )
        assertEquals(pins, ZonePinCodec.decode(ZonePinCodec.encode(pins)))
    }

    @Test fun codec_ignora_linha_ruim() {
        val ok = ZonePinCodec.encode(listOf(ZonePin(5L, ZoneType.DANGER, sp)))
        val decoded = ZonePinCodec.decode("lixo\n$ok\nv1\tx\tDANGER\t1\t2")
        assertEquals(listOf(ZonePin(5L, ZoneType.DANGER, sp)), decoded)
        assertTrue(ZonePinCodec.decode(null).isEmpty())
        assertTrue(ZonePinCodec.decode("").isEmpty())
    }

    @Test fun adicionar_e_remover() {
        var pins = ZonePins.add(emptyList(), ZonePin(2L, ZoneType.DANGER, sp))
        pins = ZonePins.add(pins, ZonePin(1L, ZoneType.CUSTOM, sp))
        assertEquals(listOf(1L, 2L), pins.map { it.id })
        pins = ZonePins.remove(pins, 2L)
        assertEquals(listOf(1L), pins.map { it.id })
    }

    @Test fun zona_rapida_envolve_o_pin() {
        val poly = ZonePins.circle(sp, radiusM = 150.0)
        assertEquals(12, poly.size)
        assertTrue(Geo.contains(poly, sp))
        poly.forEach { assertEquals(0.150, Geo.haversineKm(sp, it), 0.005) }
        assertFalse(Geo.contains(poly, GeoPoint(sp.lat + 0.003, sp.lon)))
    }
}
