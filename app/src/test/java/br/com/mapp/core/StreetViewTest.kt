package br.com.mapp.core

import br.com.mapp.core.geo.StreetFraming
import br.com.mapp.core.geo.StreetTilt
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.geo.StreetZoom
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.route.RouteStop
import br.com.mapp.core.route.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StreetViewTest {

    private val p = GeoPoint(-30.0712345, -51.1198765)

    @Test
    fun `urls levam o ponto com 5 casas e a chave`() {
        val meta = StreetView.metadataUrl(p, " abc ")
        assertTrue(meta.startsWith("https://maps.googleapis.com/maps/api/streetview/metadata?"))
        assertTrue(meta.contains("location=-30.07123,-51.11988"))
        assertTrue(meta.contains("source=outdoor"))
        assertTrue(meta.endsWith("key=abc"))

        val img = StreetView.imageUrl(p, "abc")
        assertTrue(img.startsWith("https://maps.googleapis.com/maps/api/streetview?size=640x420"))
        assertTrue(img.contains("location=-30.07123,-51.11988"))
        assertTrue(img.contains("return_error_code=true"))
        assertFalse(img.contains("heading="))
    }

    @Test
    fun `fachada e o enquadramento de antes e rua e o padrao`() {
        assertEquals(StreetFraming.STREET, StreetFraming.DEFAULT)
        assertEquals(StreetFraming.MIXED, StreetFraming.fromId("mista"))
        assertEquals(StreetFraming.DEFAULT, StreetFraming.fromId(null))
        assertEquals(StreetFraming.DEFAULT, StreetFraming.fromId("lixo"))
        // Fachada ignora a inclinação; Rua e Mista usam a escolhida.
        assertEquals(0, StreetFraming.FACADE.tilt(30))
        assertEquals(30, StreetFraming.STREET.tilt(30))
        assertEquals(30, StreetFraming.MIXED.tilt(30))
    }

    @Test
    fun `rumo da camera olha ao longo da rua`() {
        // Foto tirada 21 m a oeste do endereço: o endereço está a leste (90°), a rua corre norte-sul.
        val pano = GeoPoint(-30.0712345, -51.1201)
        assertEquals(90.0, StreetView.bearing(pano, p), 0.01)
        assertEquals(21.5, StreetView.metersBetween(pano, p), 0.5)
        assertEquals(180.0, StreetView.streetHeading(pano, p)!!, 0.01)
        // Foto 18 m ao sul: o endereço está ao norte (0°) e a rua corre leste-oeste.
        val south = GeoPoint(-30.0714, -51.1198765)
        assertEquals(90.0, StreetView.streetHeading(south, p)!!, 0.01)
        // Foto quase sobre o ponto: não há rumo confiável (volta a ser a foto que o Google escolhe).
        assertNull(StreetView.streetHeading(GeoPoint(-30.07125, -51.1198765), p))
    }

    @Test
    fun `enquadramento rua pede heading e camera para baixo`() {
        val pano = GeoPoint(-30.0712345, -51.1201)

        // Fachada = a foto de antes: sem heading e sem inclinação.
        val facade = StreetView.imageUrl(p, "abc", StreetZoom.X1, StreetFraming.FACADE, 20, pano)
        assertFalse(facade.contains("heading="))
        assertTrue(facade.contains("pitch=0&"))

        // Rua: câmera girada 90° (olhando a pista) e inclinada para baixo.
        val street = StreetView.imageUrl(p, "abc", StreetZoom.X1, StreetFraming.STREET, 20, pano)
        assertTrue(street.contains("heading=180.0&"))
        assertTrue(street.contains("pitch=-20&"))

        // Sem o ponto da foto (metadados sem `location`): fica o rumo do Google, só inclinada.
        val noPano = StreetView.imageUrl(p, "abc", StreetZoom.X1, StreetFraming.STREET, 20, null)
        assertFalse(noPano.contains("heading="))
        assertTrue(noPano.contains("pitch=-20&"))

        // Mista: rumo da fachada (sem heading) com a câmera inclinada.
        val mixed = StreetView.imageUrl(p, "abc", StreetZoom.X1, StreetFraming.MIXED, 45, pano)
        assertFalse(mixed.contains("heading="))
        assertTrue(mixed.contains("pitch=-45&"))
    }

    @Test
    fun `inclinacao anda de 5 em 5 graus dentro da faixa`() {
        assertEquals(20, StreetTilt.DEFAULT)
        assertEquals(0, StreetTilt.snap(-10))
        assertEquals(45, StreetTilt.snap(90))
        assertEquals(20, StreetTilt.snap(22))
        assertEquals(25, StreetTilt.snap(23))
        assertEquals("reta", StreetTilt.label(0))
        assertEquals("20° para baixo", StreetTilt.label(20))
        assertEquals(StreetTilt.MAX / StreetTilt.STEP - 1, StreetTilt.SLIDER_STEPS)
    }

    @Test
    fun `enquadramento e inclinacao mudam a foto guardada`() {
        val facade = StreetView.cacheKey(p, StreetZoom.X1, StreetFraming.FACADE, 20)
        // Fachada guarda com o mesmo nome de antes: a foto já buscada continua valendo.
        assertEquals(StreetView.cacheKey(p, StreetZoom.X1), facade)
        val street = StreetView.cacheKey(p, StreetZoom.X1, StreetFraming.STREET, 20)
        assertFalse(facade == street)
        assertFalse(street == StreetView.cacheKey(p, StreetZoom.X1, StreetFraming.MIXED, 20))
        assertFalse(street == StreetView.cacheKey(p, StreetZoom.X1, StreetFraming.STREET, 35))
        assertEquals(street, StreetView.cacheKey(p, StreetZoom.X1, StreetFraming.STREET, 22))
    }

    @Test
    fun `zoom diminui o campo de visao da foto`() {
        assertEquals(80, StreetZoom.X1.fov) // como era antes
        assertEquals(58, StreetZoom.X1_5.fov)
        assertEquals(46, StreetZoom.X2.fov)
        assertEquals(31, StreetZoom.X3.fov)
        assertEquals(StreetZoom.X1, StreetZoom.DEFAULT) // padrão = como antes
        assertTrue(StreetView.imageUrl(p, "abc").contains("fov=80&"))
        assertTrue(StreetView.imageUrl(p, "abc", StreetZoom.X2).contains("fov=46&"))
        assertEquals(StreetZoom.X2_5, StreetZoom.fromId("2.5"))
        assertEquals(StreetZoom.DEFAULT, StreetZoom.fromId(null))
        assertEquals(StreetZoom.DEFAULT, StreetZoom.fromId("lixo"))
    }

    @Test
    fun `zoom diferente nao usa a mesma foto`() {
        assertFalse(StreetView.cacheKey(p, StreetZoom.X1) == StreetView.cacheKey(p, StreetZoom.X2))
        assertEquals(StreetView.cacheKey(p, StreetZoom.X2), StreetView.cacheKey(GeoPoint(-30.0712349, -51.1198801), StreetZoom.X2))
    }

    @Test
    fun `mesma foto para pontos a menos de 1 metro`() {
        assertEquals(StreetView.cacheKey(p), StreetView.cacheKey(GeoPoint(-30.0712349, -51.1198801)))
        assertFalse(StreetView.cacheKey(p) == StreetView.cacheKey(GeoPoint(-30.0713, -51.1199)))
    }

    private fun stop(letter: String, kind: StopKind, address: String? = "Rua $letter", point: GeoPoint? = null) =
        RouteStop(letter, kind, address, point)

    @Test
    fun `corrida direta tem coleta e destino`() {
        val route = listOf(stop("A", StopKind.PICKUP), stop("B", StopKind.DROPOFF))
        assertEquals(listOf("A", "B"), StreetView.pick(route).map { it.letter })
    }

    @Test
    fun `parada sem endereco nem ponto fica sem foto`() {
        val route = listOf(stop("A", StopKind.PICKUP), stop("B", StopKind.STOP, address = null), stop("C", StopKind.DROPOFF))
        assertEquals(listOf("A", "C"), StreetView.pick(route).map { it.letter })
    }

    @Test
    fun `no maximo 3 fotos e o destino sempre entra`() {
        val route = listOf(
            stop("A", StopKind.PICKUP),
            stop("B", StopKind.STOP),
            stop("C", StopKind.STOP),
            stop("D", StopKind.DROPOFF),
        )
        assertEquals(listOf("A", "B", "D"), StreetView.pick(route).map { it.letter })
        val oneStop = listOf(stop("A", StopKind.PICKUP), stop("B", StopKind.STOP), stop("C", StopKind.DROPOFF))
        assertEquals(listOf("A", "B", "C"), StreetView.pick(oneStop).map { it.letter })
    }

    @Test
    fun `credito e data da foto`() {
        assertEquals("05/2023", StreetView.dateLabel("2023-05"))
        assertNull(StreetView.dateLabel("maio"))
        assertNull(StreetView.dateLabel(null))
        assertEquals("© Google · 05/2023", StreetView.credit("© Google", "2023-05"))
        assertEquals("© Google", StreetView.credit("", null))
    }

    @Test
    fun `contador do mes para antes das fotos gratis`() {
        var b = StreetViewBudget.decode(null)
        assertEquals(0, b.usedIn("2026-11"))
        b = b.spend("2026-11").spend("2026-11")
        assertEquals(2, b.usedIn("2026-11"))
        assertEquals(b, StreetViewBudget.decode(b.encode()))
        // Mês novo zera.
        assertEquals(0, b.usedIn("2026-12"))
        assertEquals(1, b.spend("2026-12").usedIn("2026-12"))

        val full = StreetViewBudget("2026-11", StreetView.LIMIT_PER_MONTH)
        assertFalse(full.canSpend("2026-11"))
        assertTrue(full.canSpend("2026-12"))
        assertTrue(StreetView.LIMIT_PER_MONTH < StreetView.FREE_PER_MONTH)
    }

    @Test
    fun `mes pelo fuso do celular`() {
        // 01/12/2026 01:00 UTC = 30/11 22:00 em Brasília.
        val ms = 1_796_086_800_000L
        assertEquals("2026-11", StreetViewBudget.monthOf(ms, ZoneId.of("America/Sao_Paulo")))
        assertEquals("2026-12", StreetViewBudget.monthOf(ms, ZoneId.of("UTC")))
    }

    @Test
    fun `texto estragado vira contador zerado`() {
        assertEquals(StreetViewBudget(), StreetViewBudget.decode("lixo"))
        assertEquals(0, StreetViewBudget.decode("2026-11:-3").used)
    }
}
