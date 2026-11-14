package br.com.mapp.core

import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.geo.StreetViewBudget
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
