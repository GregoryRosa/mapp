package br.com.mapp.reader

import br.com.mapp.core.model.PointRole
import br.com.mapp.reader.parser.ParseUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseUtilsTest {
    private val eps = 1e-9

    @Test fun dinheiro_formatos_brasileiros() {
        assertEquals(12.50, ParseUtils.parseMoney("R$ 12,50")!!, eps)
        assertEquals(12.50, ParseUtils.parseMoney("R$12.50")!!, eps)
        assertEquals(1234.56, ParseUtils.parseMoney("R$ 1.234,56")!!, eps)
        assertEquals(1234.56, ParseUtils.parseMoney("R$ 1,234.56")!!, eps)
        assertEquals(1234.0, ParseUtils.parseMoney("R$ 1.234")!!, eps)
        assertEquals(18.0, ParseUtils.parseMoney("R$ 18")!!, eps)
        assertEquals(9.9, ParseUtils.parseMoney("Aceitar por R\$ 9,90")!!, eps)
        assertEquals(7.5, ParseUtils.parseMoney("~R$ 7,5")!!, eps)
    }

    @Test fun dinheiro_ausente() {
        assertNull(ParseUtils.parseMoney("12,50"))
        assertNull(ParseUtils.parseMoney("UberX"))
        assertFalse(ParseUtils.hasMoney("4 min (1,2 km)"))
    }

    @Test fun dinheiro_somente_preco() {
        assertTrue(ParseUtils.isMoneyOnly("R$ 18,40"))
        assertTrue(ParseUtils.isMoneyOnly("R$ 18,40 - R$ 22,10"))
        assertFalse(ParseUtils.isMoneyOnly("Inclui R$ 2,50 de Dinâmico"))
    }

    @Test fun numero_decimal_sem_agrupamento() {
        assertEquals(1.234, ParseUtils.parseNumber("1.234")!!, eps)
        assertEquals(1234.0, ParseUtils.parseNumber("1.234", allowGrouping = true)!!, eps)
        assertNull(ParseUtils.parseNumber("abc"))
    }

    @Test fun distancias_km_e_metros() {
        assertEquals(1.2, ParseUtils.parseDistanceKm("4 min (1,2 km) de distância")!!, eps)
        assertEquals(12.5, ParseUtils.parseDistanceKm("12.5km")!!, eps)
        assertEquals(0.85, ParseUtils.parseDistanceKm("850 m")!!, eps)
        assertEquals(0.3, ParseUtils.parseDistanceKm("a 300 metros")!!, eps)
        assertEquals(3.0, ParseUtils.parseDistanceKm("3 quilômetros")!!, eps)
    }

    @Test fun distancia_nao_confunde_minutos_nem_rodovia() {
        assertNull(ParseUtils.parseDistanceKm("12 min"))
        assertNull(ParseUtils.parseDistanceKm("Rodovia SP-280, km 25"))
    }

    @Test fun duracoes() {
        assertEquals(12, ParseUtils.parseDurationMin("12 min"))
        assertEquals(4, ParseUtils.parseDurationMin("4min (1,2 km)"))
        assertEquals(65, ParseUtils.parseDurationMin("1 h 5 min (45 km)"))
        assertEquals(65, ParseUtils.parseDurationMin("1h05"))
        assertEquals(130, ParseUtils.parseDurationMin("2 horas e 10 minutos"))
        assertEquals(60, ParseUtils.parseDurationMin("1 h"))
        assertEquals(25, ParseUtils.parseDurationMin("Viagem: 25 minutos"))
    }

    @Test fun duracao_ignora_ha_x_min_e_segundos() {
        assertNull(ParseUtils.parseDurationMin("há 3 min"))
        assertNull(ParseUtils.parseDurationMin("2 min atrás"))
        assertNull(ParseUtils.parseDurationMin("Aceitar (15 s)"))
        assertEquals(2, ParseUtils.findDurations("1,2 km · 2 min").single().value.toInt())
    }

    @Test fun nota_do_passageiro() {
        assertEquals(4.92, ParseUtils.parseRating("★ 4,92")!!, eps)
        assertEquals(4.9, ParseUtils.parseRating("4,9 ★ (213)")!!, eps)
        assertEquals(4.95, ParseUtils.parseRating("⭐️ 4.95")!!, eps)
        assertEquals(5.0, ParseUtils.parseRating("★ 5")!!, eps)
        assertEquals(4.93, ParseUtils.parseRating("4,93")!!, eps)
        assertEquals(4.8, ParseUtils.parseRating("Nota: 4,80")!!, eps)
        assertNull(ParseUtils.parseRating("1,2"))
        assertNull(ParseUtils.parseRating("R$ 4,90"))
        assertNull(ParseUtils.parseRating("★ 7,5"))
    }

    @Test fun endereco_heuristica() {
        assertTrue(ParseUtils.looksLikeAddress("Rua Augusta, 1500 - Consolação"))
        assertTrue(ParseUtils.looksLikeAddress("Av. Paulista, 900"))
        assertTrue(ParseUtils.looksLikeAddress("R. das Flores 45"))
        assertTrue(ParseUtils.looksLikeAddress("Alameda Santos"))
        assertTrue(ParseUtils.looksLikeAddress("Centro, Campinas - SP"))
        assertTrue(ParseUtils.looksLikeAddress("Shopping Iguatemi"))

        assertFalse(ParseUtils.looksLikeAddress("UberX"))
        assertFalse(ParseUtils.looksLikeAddress("Carlos"))
        assertFalse(ParseUtils.looksLikeAddress("R$ 18,40"))
        assertFalse(ParseUtils.looksLikeAddress("4 min (1,2 km) de distância"))
        assertFalse(ParseUtils.looksLikeAddress("★ 4,92"))
        assertFalse(ParseUtils.looksLikeAddress("Dinheiro - Pix"))
        assertFalse(ParseUtils.looksLikeAddress("Aceitar"))
        assertFalse(ParseUtils.looksLikeAddress("99Pop - Comfort", blocked = listOf("99Pop - Comfort")))
    }

    @Test fun endereco_com_rotulo() {
        val dest = ParseUtils.extractAddress("Destino: Av. Brasil, 2000 - Jardim América")!!
        assertEquals("Av. Brasil, 2000 - Jardim América", dest.address)
        assertEquals(PointRole.DROPOFF, dest.role)
        val orig = ParseUtils.extractAddress("Embarque: Rua das Flores, 45")!!
        assertEquals(PointRole.PICKUP, orig.role)
        assertNull(ParseUtils.extractAddress("Destino:"))
    }

    @Test fun linhas_quebram_e_normalizam() {
        val lines = ParseUtils.splitLines(listOf("UberX\nR\$ 18,40", "  ", "  Rua A,  10 "))
        assertEquals(listOf("UberX", "R$ 18,40", "Rua A, 10"), lines)
    }
}
