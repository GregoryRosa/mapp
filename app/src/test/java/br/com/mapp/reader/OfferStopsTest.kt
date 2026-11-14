package br.com.mapp.reader

import br.com.mapp.reader.parser.ParseUtils
import br.com.mapp.reader.parser.UberParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corridas com paradas no caminho. Formato REAL da Uber (print de 14/11/2026): "1 parada" fica
 * entre o endereço da coleta e o trecho da viagem, sem o endereço da parada, e o botão é
 * "Selecionar". As outras amostras são inventadas (outros formatos possíveis).
 */
class OfferStopsTest {

    /** Texto do print real (Belém), como o OCR entrega: endereço da coleta quebrado em 2 linhas. */
    private val realOcr = listOf(
        "UberX", "R$ 15,19", "* 4,95 (283)",
        "13 min (3.8 km)", "Travessa Honório José dos Santos,", "Jurunas, Belém",
        "1 parada",
        "19 minutos (5.4 km)", "Av. Alcindo Cacela, 2144, Nazaré, Belém",
        "Selecionar",
    )

    @Test fun uber_print_real_imagem() {
        val o = UberParser.parse(realOcr, fromOcr = true)!!
        assertEquals(15.19, o.price, 1e-9)
        assertEquals(13, o.pickupMin)
        assertEquals(19, o.tripMin)
        assertEquals("Travessa Honório José dos Santos, Jurunas, Belém", o.pickupAddress)
        assertEquals("Av. Alcindo Cacela, 2144, Nazaré, Belém", o.dropoffAddress)
        assertEquals(1, o.stopCount)
        assertTrue(o.stopAddresses.isEmpty())
    }

    @Test fun uber_print_real_texto() {
        val o = UberParser.parse(
            listOf(
                "UberX", "R$ 15,19", "★ 4,95 (283)",
                "13 min (3.8 km)", "Travessa Honório José dos Santos, Jurunas, Belém",
                "1 parada",
                "19 minutos (5.4 km)", "Av. Alcindo Cacela, 2144, Nazaré, Belém",
                "Selecionar",
            ),
        )!!
        assertEquals("Travessa Honório José dos Santos, Jurunas, Belém", o.pickupAddress)
        assertEquals("Av. Alcindo Cacela, 2144, Nazaré, Belém", o.dropoffAddress)
        assertEquals(1, o.stopCount)
        assertTrue(o.stopAddresses.isEmpty())
    }

    @Test fun uber_print_real_sem_parada_continua_igual() {
        val o = UberParser.parse(realOcr.filter { it != "1 parada" }, fromOcr = true)!!
        assertEquals("Travessa Honório José dos Santos, Jurunas, Belém", o.pickupAddress)
        assertEquals("Av. Alcindo Cacela, 2144, Nazaré, Belém", o.dropoffAddress)
        assertEquals(0, o.stopCount)
    }

    @Test fun aviso_de_paradas() {
        assertEquals(1, ParseUtils.stopMarker("Várias paradas"))
        assertEquals(1, ParseUtils.stopMarker("Múltiplas paradas"))
        assertEquals(2, ParseUtils.stopMarker("+2 paradas"))
        assertEquals(2, ParseUtils.stopMarker("Viagem com 2 paradas"))
        assertEquals(1, ParseUtils.stopMarker("Com parada"))
        assertEquals(1, ParseUtils.stopMarker("Paradas adicionais"))
        assertEquals(1, ParseUtils.stopMarker("Multiple stops"))
        assertEquals(3, ParseUtils.stopMarker("3 paradas · +R$ 4,00"))
        // Bairro/rua com "Parada" no nome não é aviso.
        assertNull(ParseUtils.stopMarker("Parada Inglesa, São Paulo"))
        assertNull(ParseUtils.stopMarker("Rua da Parada, 10"))
        assertNull(ParseUtils.stopMarker("Viagem: 22 min (12,5 km)"))
        // Rótulo sozinho e parada rotulada.
        assertTrue(ParseUtils.isStopLabelOnly("Parada 2"))
        assertFalse(ParseUtils.isStopLabelOnly("Parada Inglesa"))
        assertEquals("Rua Oscar Freire, 200 - Jardins", ParseUtils.labeledStop("Parada: Rua Oscar Freire, 200 - Jardins"))
        assertEquals("Rua Oscar Freire, 200", ParseUtils.labeledStop("1ª parada - Rua Oscar Freire, 200"))
        assertNull(ParseUtils.labeledStop("Parada Inglesa, São Paulo"))
        // Aviso nunca vira endereço; bairro "Parada Inglesa" continua sendo endereço.
        assertFalse(ParseUtils.looksLikeAddress("Várias paradas"))
        assertFalse(ParseUtils.isAddressPart("Várias paradas"))
        assertTrue(ParseUtils.looksLikeAddress("Parada Inglesa, São Paulo"))
    }

    @Test fun uber_tres_enderecos_com_aviso_o_do_meio_e_parada() {
        val o = UberParser.parse(
            listOf(
                "UberX", "R$ 32,10", "★ 4,90",
                "5 min (1,8 km) de distância", "Rua Augusta, 1500 - Consolação",
                "Viagem: 35 min (16,2 km)", "Várias paradas",
                "Rua Oscar Freire, 200 - Jardins",
                "Av. Paulista, 900 - Bela Vista",
                "Aceitar",
            ),
        )!!
        assertEquals("Rua Augusta, 1500 - Consolação", o.pickupAddress)
        assertEquals(listOf("Rua Oscar Freire, 200 - Jardins"), o.stopAddresses)
        assertEquals("Av. Paulista, 900 - Bela Vista", o.dropoffAddress)
        assertEquals(1, o.stopCount)
    }

    @Test fun uber_parada_rotulada() {
        val o = UberParser.parse(
            listOf(
                "UberX", "R$ 32,10",
                "5 min (1,8 km) de distância", "Rua Augusta, 1500 - Consolação",
                "Viagem: 35 min (16,2 km)",
                "Parada: Rua Oscar Freire, 200 - Jardins",
                "Av. Paulista, 900 - Bela Vista",
                "Aceitar",
            ),
        )!!
        assertEquals("Rua Augusta, 1500 - Consolação", o.pickupAddress)
        assertEquals("Av. Paulista, 900 - Bela Vista", o.dropoffAddress)
        assertEquals(listOf("Rua Oscar Freire, 200 - Jardins"), o.stopAddresses)
        assertEquals(1, o.stopCount)
    }

    @Test fun uber_aviso_sem_endereco_da_parada() {
        val o = UberParser.parse(
            listOf(
                "UberX", "R$ 32,10",
                "5 min (1,8 km) de distância", "Rua Augusta, 1500 - Consolação",
                "Viagem: 35 min (16,2 km)", "2 paradas",
                "Av. Paulista, 900 - Bela Vista",
                "Aceitar",
            ),
        )!!
        assertEquals("Rua Augusta, 1500 - Consolação", o.pickupAddress)
        assertEquals("Av. Paulista, 900 - Bela Vista", o.dropoffAddress)
        assertTrue(o.stopAddresses.isEmpty())
        assertEquals(2, o.stopCount)
    }

    @Test fun uber_direta_e_bairro_parada_inglesa_nao_viram_parada() {
        val o = UberParser.parse(
            listOf(
                "UberX", "R$ 18,40",
                "4 min (1,2 km) de distância", "Rua Augusta, 1500 - Consolação",
                "Viagem: 22 min (12,5 km)", "Av. Luiz Dumont Villares, 400 - Parada Inglesa",
                "Aceitar",
            ),
        )!!
        assertEquals("Av. Luiz Dumont Villares, 400 - Parada Inglesa", o.dropoffAddress)
        assertTrue(o.stopAddresses.isEmpty())
        assertEquals(0, o.stopCount)
    }

    @Test fun uber_ocr_paradas_em_blocos() {
        val o = UberParser.parse(
            listOf(
                "UberX", "R$ 32,10", "4,90",
                "5 min (1.8 km) de distância", "Rua Augusta, 1500 -", "Consolação",
                "Viagem de 35 min (16.2 km)", "Várias paradas",
                "Rua Oscar Freire, 200 -", "Jardins",
                "Av. Paulista, 900 - Bela", "Vista",
                "Aceitar",
            ),
            fromOcr = true,
        )!!
        assertEquals("Rua Augusta, 1500 - Consolação", o.pickupAddress)
        assertEquals(listOf("Rua Oscar Freire, 200 - Jardins"), o.stopAddresses)
        assertEquals("Av. Paulista, 900 - Bela Vista", o.dropoffAddress)
        assertEquals(1, o.stopCount)
    }
}
