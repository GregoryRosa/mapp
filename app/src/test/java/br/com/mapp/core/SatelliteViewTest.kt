package br.com.mapp.core

import br.com.mapp.core.geo.PhotoView
import br.com.mapp.core.geo.SatelliteShot
import br.com.mapp.core.geo.SatelliteView
import br.com.mapp.core.geo.SatelliteZoom
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class SatelliteViewTest {

    private val p = GeoPoint(-30.0712345, -51.1198765)
    private val green = 0xFF2E7D32.toInt()
    private val red = 0xFFC62828.toInt()

    @Test
    fun `url do satelite centra no ponto com marcador na letra e cor do mapa`() {
        val url = SatelliteView.imageUrl(p, " abc ", "A", green)
        assertTrue(url.startsWith("https://maps.googleapis.com/maps/api/staticmap?"))
        assertTrue(url.contains("center=-30.07123,-51.11988"))
        assertTrue(url.contains("zoom=17"))
        assertTrue(url.contains("size=640x420"))
        assertTrue(url.contains("maptype=hybrid"))
        assertTrue(url.endsWith("key=abc"))
        val markers = URLDecoder.decode(url.substringAfter("markers=").substringBefore('&'), "UTF-8")
        assertEquals("color:0x2E7D32|label:A|-30.07123,-51.11988", markers)
    }

    @Test
    fun `faixa da notificacao usa outro tamanho e zoom mais aberto`() {
        val url = SatelliteView.imageUrl(p, "k", "B", red, SatelliteShot.STRIP)
        assertTrue(url.contains("size=480x150"))
        assertTrue(url.contains("zoom=16"))
        val big = SatelliteView.imageUrl(p, "k", "B", red, SatelliteShot(2000, 900, 15))
        assertTrue(big.contains("size=640x640")) // a API aceita até 640 de cada lado
    }

    @Test
    fun `zoom do satelite soma ao zoom do formato`() {
        assertEquals(SatelliteZoom.X8, SatelliteZoom.DEFAULT)
        assertEquals(20, SatelliteShot.CARD.zoomed(SatelliteZoom.DEFAULT).zoom) // ~90 m no Card
        assertEquals(19, SatelliteShot.STRIP.zoomed(SatelliteZoom.DEFAULT).zoom)
        assertEquals(18, SatelliteShot.STRIP.zoomed(SatelliteZoom.X4).zoom)
        assertEquals(21, SatelliteShot.CARD.zoomed(SatelliteZoom.X16).zoom) // máximo da API
        assertEquals(17, SatelliteShot.CARD.zoomed(SatelliteZoom.X1).zoom) // como antes
        assertEquals(SatelliteShot.MAX_ZOOM, SatelliteShot(640, 420, 20).zoomed(SatelliteZoom.X16).zoom)
        val url = SatelliteView.imageUrl(p, "k", "A", green, SatelliteShot.CARD.zoomed(SatelliteZoom.X4))
        assertTrue(url.contains("zoom=19"))
        assertNotEquals(
            SatelliteView.cacheKey(p, "A", green, SatelliteShot.CARD.zoomed(SatelliteZoom.X1)),
            SatelliteView.cacheKey(p, "A", green, SatelliteShot.CARD.zoomed(SatelliteZoom.X4)),
        )
        assertEquals(SatelliteZoom.X2, SatelliteZoom.fromId("1"))
        assertEquals(SatelliteZoom.DEFAULT, SatelliteZoom.fromId(null))
    }

    @Test
    fun `rotulo do marcador so letra maiuscula ou numero`() {
        assertEquals("C", SatelliteView.label("c"))
        assertEquals("7", SatelliteView.label(" 7 "))
        assertNull(SatelliteView.label(""))
        assertNull(SatelliteView.label("•"))
        val noLabel = SatelliteView.imageUrl(p, "k", "", red)
        assertFalse(URLDecoder.decode(noLabel, "UTF-8").contains("label:"))
    }

    @Test
    fun `cor ARGB vira 0xRRGGBB`() {
        assertEquals("0x2E7D32", SatelliteView.hex(green))
        assertEquals("0x000000", SatelliteView.hex(0xFF000000.toInt()))
    }

    @Test
    fun `cache separa ponto letra tamanho e nao mistura com a foto da rua`() {
        val a = SatelliteView.cacheKey(p, "A", green)
        assertEquals(a, SatelliteView.cacheKey(GeoPoint(-30.0712349, -51.1198801), "A", green))
        assertNotEquals(a, SatelliteView.cacheKey(p, "B", red))
        assertNotEquals(a, SatelliteView.cacheKey(p, "A", green, SatelliteShot.STRIP))
        assertNotEquals(a, StreetView.cacheKey(p))
    }

    @Test
    fun `modo da foto alterna e le texto salvo`() {
        assertEquals(PhotoView.SATELLITE, PhotoView.STREET.other())
        assertEquals(PhotoView.STREET, PhotoView.SATELLITE.other())
        assertEquals(PhotoView.SATELLITE, PhotoView.fromId("satellite"))
        assertEquals(PhotoView.STREET, PhotoView.fromId(null))
        assertEquals(PhotoView.STREET, PhotoView.fromId("lixo"))
    }

    @Test
    fun `contador do satelite usa o proprio limite`() {
        val month = "2026-11"
        val almost = StreetViewBudget(month, SatelliteView.LIMIT_PER_MONTH - 1)
        assertTrue(almost.canSpend(month, SatelliteView.LIMIT_PER_MONTH))
        assertFalse(almost.spend(month).canSpend(month, SatelliteView.LIMIT_PER_MONTH))
        assertTrue(StreetViewBudget(month, 9_499).canSpend(month)) // padrão = limite da foto da rua
        assertTrue(SatelliteView.LIMIT_PER_MONTH < SatelliteView.FREE_PER_MONTH)
    }
}
