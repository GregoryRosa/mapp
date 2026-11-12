package br.com.mapp.reader

import br.com.mapp.core.model.RawOffer
import br.com.mapp.reader.parser.OcrBox
import br.com.mapp.reader.parser.OcrLayout
import br.com.mapp.reader.parser.OcrLine
import br.com.mapp.reader.parser.ParseUtils
import br.com.mapp.reader.parser.UberParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leitura por imagem (OCR). As linhas abaixo são a saída REAL de dois motores de OCR (Tesseract e
 * PP-OCR) sobre os prints do cartão da Uber de 12/11/2026 — cada motor erra de um jeito (ícone vira
 * letra, "•" vira "«"/"*"/"." ou some, espaços e acentos somem, botão Aceitar não é lido).
 * No aparelho o motor é o ML Kit; ao receber um diagnóstico com texto dele, adicione aqui.
 */
class OcrParsingTest {
    private val eps = 1e-9

    // ------------------------------------------------------------------ UberX R$ 24,54 (tela cheia)

    private val uberxTesseract = listOf(
        "10:19 € --", "Q NBile -", "3º Regimento de", "Guarda de", "Cavalaria", "Ç", "*", "Exclusivo",
        "R$ 24,54", "Est. de R$1,50/km", "G", "=.", "O", "=", "+10% de ganhos Uber Pro",
        "O] 6 min « 1.6 km", "Av. Sen. Salgado Filho, Passo do", "Sabão, Viamão", "x 4,90 (989)",
        "[0] 29 minutos * 14.8 km", "R. Dona Laura, 320, Bairro Rio", "Branco, Porto Alegre", "e",
    )

    private val uberxPpOcr = listOf(
        "5G", "10:19", "66", "3Regimento de", "Guardade", "Cavalaria", "Uberx", "Exclusivo",
        "R$ 24,54", "Est.deR$1,50/km", "+10%deganhosuberPro", "6 min1.6km",
        "Av.Sen.Salgado Filho, Passo do", "Sabao,Viamao", "4,90 (989)", "29 minutos·14.8km",
        "R.DonaLaura,320,BairroRio", "Branco,PortoAlegre", "Aceitar",
    )

    private fun assertUberX(o: RawOffer) {
        assertEquals(24.54, o.price, eps)
        assertEquals(6, o.pickupMin)
        assertEquals(1.6, o.pickupKm!!, eps)
        assertEquals(29, o.tripMin)
        assertEquals(14.8, o.tripKm!!, eps)
        assertEquals(4.90, o.passengerRating!!, eps)
    }

    @Test fun uberx_tesseract_sem_botao_aceitar() {
        val o = UberParser.parse(uberxTesseract, fromOcr = true)!!
        assertUberX(o)
        assertEquals("Av. Sen. Salgado Filho, Passo do Sabão, Viamão", o.pickupAddress)
        assertEquals("R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", o.dropoffAddress)
    }

    @Test fun uberx_ppocr_sem_espacos() {
        val o = UberParser.parse(uberxPpOcr, fromOcr = true)!!
        assertUberX(o)
        assertEquals("Av.Sen.Salgado Filho, Passo do Sabao,Viamao", o.pickupAddress)
        assertEquals("R.DonaLaura,320,BairroRio Branco,PortoAlegre", o.dropoffAddress)
    }

    @Test fun uberx_imagem_reduzida() {
        // Mesmo print reduzido para 900 px e 720 px de largura (o app reduz a imagem antes do OCR).
        val ppOcr900 = listOf(
            "Cavalaria", "Uberx", "Exclusivo", "R$ 24,54", "Est. de R$1,50/km", "+10%deganhosuberPro",
            "6 min : 1.6 km", "Av.Sen.Salgado Filho,Passo do", "Sabao,Viamao", "4,90 (989)", "29 minutos14.8 km",
            "R. Dona Laura, 320, Bairro Rio", "Branco, Porto Alegre", "Aceitar",
        )
        val tesseract720 = listOf(
            "3º Regimento de", "Guarda de", "Cavalaria", "c Exclusivo", "R$ 24,54", "Est. de R$1,50/km", "-", "s", "G",
            "+10% de ganhos Uber Pro", "e 6 min « 1.6 km", "Av. Sen. Salgado Filho, Passo do", "Sabão, Viamão",
            "x 4,90 (989)", "(+) 29 minutos * 14.8 km", "R. Dona Laura, 320, Bairro Rio", "Branco, Porto Alegre", "te UR",
        )
        val ppOcr720 = listOf(
            "Uberx", "Exclusivo", "R$ 24,54", "Est. de R$1,50/km", "+10% de ganhos uber Pro", "6 min :1.6 km",
            "Av. Sen. Salgado Filho, Passo do", "Sabao, Viamao", "★ 4,90 (989)", "29 minutos :14.8 km",
            "R. Dona Laura, 320, Bairro Rio", "Branco, Porto Alegre", "Aceitar",
        )
        for (texts in listOf(ppOcr900, tesseract720, ppOcr720)) {
            val o = UberParser.parse(texts, fromOcr = true)!!
            assertUberX(o)
            assertEquals("R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", o.dropoffAddress)
        }
    }

    // ------------------------------------------------------------------ Comfort R$ 14,41 (por cima de outro app)

    private val comfortTesseract = listOf(
        "rendeu um alerta sem ter chamada;", "O botão iniciar copiloto e parar não", "fazem nada;",
        "Preciso que fique um icone em overlay", "Exclusivo", "x", "R$ 14,41", "Est. de R$1,40/km", "=", "—",
        "+10% de ganhos Uber Pro", "(E) 9 min «3.9 km", "R. Araras, Parque Indio Jari, Viamão", "*x 4,92(640)",
        "(+) 16 minutos * 6.4 km", "Rua Atenas, 70, Morro Santana,", "Porto Alegre", "» ECO",
    )

    private val comfortPpOcr = listOf(
        "km-3min", "RS/h abaixode30", "+R$4", "Uber", "Comfort", "Exclusivo", "R$ 14,41", "Est. de R\$l,40/km",
        "+10% de ganhos uber Pro", "9 min.3.9 km", "R.Araras,Pargue Indio Jari,Viamao", "4,92 (640)",
        "16 minutos·6.4 km", "RuaAtenas,70,MorroSantana,", "Porto Alegre", "Aceitar",
    )

    private fun assertComfort(o: RawOffer) {
        assertEquals(14.41, o.price, eps)
        assertEquals(9, o.pickupMin)
        assertEquals(3.9, o.pickupKm!!, eps)
        assertEquals(16, o.tripMin)
        assertEquals(6.4, o.tripKm!!, eps)
        assertEquals(4.92, o.passengerRating!!, eps)
    }

    @Test fun comfort_tesseract() {
        val o = UberParser.parse(comfortTesseract, fromOcr = true)!!
        assertComfort(o)
        assertEquals("R. Araras, Parque Indio Jari, Viamão", o.pickupAddress)
        assertEquals("Rua Atenas, 70, Morro Santana, Porto Alegre", o.dropoffAddress)
    }

    @Test fun comfort_ppocr() {
        val o = UberParser.parse(comfortPpOcr, fromOcr = true)!!
        assertComfort(o)
        assertEquals("R.Araras,Pargue Indio Jari,Viamao", o.pickupAddress)
        assertEquals("RuaAtenas,70,MorroSantana, Porto Alegre", o.dropoffAddress)
    }

    @Test fun texto_ideal_e_trecho_partido_em_dois() {
        // O que um OCR sem erros devolveria (espaços, acentos, "•" e "★" certos).
        val ideal = listOf(
            "UberX", "Exclusivo", "R$ 24,54", "Est. de R$1,50/km", "+10% de ganhos Uber Pro", "6 min • 1.6 km",
            "Av. Sen. Salgado Filho, Passo do", "Sabão, Viamão", "★ 4,90 (989)", "29 minutos • 14.8 km",
            "R. Dona Laura, 320, Bairro Rio", "Branco, Porto Alegre", "Aceitar",
        )
        val o = UberParser.parse(ideal, fromOcr = true)!!
        assertUberX(o)
        assertEquals("Av. Sen. Salgado Filho, Passo do Sabão, Viamão", o.pickupAddress)
        assertEquals("R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", o.dropoffAddress)

        // Tempo e distância reconhecidos como dois pedaços da mesma linha; "•" sumiu ou virou ".".
        val partido = listOf(
            "R$ 24,54", "6 min", "1.6 km", "Av. Sen. Salgado Filho, Passo do", "Sabão, Viamão", "4,90 (989)",
            "29 minutos . 14.8 km", "R. Dona Laura, 320, Bairro Rio", "Branco, Porto Alegre", "Aceitar",
        )
        val p = UberParser.parse(partido, fromOcr = true)!!
        assertUberX(p)
        assertEquals("Av. Sen. Salgado Filho, Passo do Sabão, Viamão", p.pickupAddress)
        assertEquals("R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", p.dropoffAddress)
    }

    @Test fun cidade_repetida_nos_dois_enderecos_nao_some() {
        val o = UberParser.parse(
            listOf(
                "R$ 19,00", "5 min • 2.0 km", "Av. Ipiranga, 6681, Partenon,", "Porto Alegre", "4,95 (120)",
                "5 min • 2.0 km", "Rua Atenas, 70, Morro Santana,", "Porto Alegre", "Aceitar",
            ),
            fromOcr = true,
        )!!
        assertEquals(5, o.pickupMin)
        assertEquals(5, o.tripMin) // busca e viagem iguais não viram um trecho só
        assertEquals("Av. Ipiranga, 6681, Partenon, Porto Alegre", o.pickupAddress)
        assertEquals("Rua Atenas, 70, Morro Santana, Porto Alegre", o.dropoffAddress)
    }

    // ------------------------------------------------------------------ o que NÃO é oferta

    @Test fun tela_inicial_com_o_cartao_do_mapp_por_cima_nao_e_oferta() {
        // Print das 9:46: tela inicial da Uber + cartão (alerta falso) do próprio MApp.
        val tesseract = listOf(
            "9:46 (O --", "R$ 0,00 -", "R$/km", "R$/hora", "Lucro líquido", "Total: — km - 3 min",
            "* R$/h abaixo de 30", "ua Madre Sins", "jardim", "dianeira", "Rua Alca", "Irma", "Parque Re",
            "Procurando viagens",
        )
        val ppOcr = listOf(
            "9:46", "71", "R$ 0,00", "VERMELHO", "R$0,00 - X", "Uber", "+R", "RS/km", "RS/hora", "Lucroliquido",
            "Total:-km3min", "·RS/habaixode30", "RuaMadreSiNa", "1-3min", "Rua das Dalias", "+R$ 4",
            "Procurando viagens",
        )
        assertNull(UberParser.parse(tesseract, fromOcr = true))
        assertNull(UberParser.parse(ppOcr, fromOcr = true))
    }

    @Test fun um_trecho_sem_botao_aceitar_nao_e_oferta_no_ocr() {
        // Tela de navegação: ganhos do dia no topo e tempo/distância até o destino.
        val navegacao = listOf("R$ 85,20", "12 min • 5.3 km", "Av. Bento Gonçalves, 1000")
        assertNull(UberParser.parse(navegacao, fromOcr = true))
        // Com o botão é oferta (ex.: cartão sem o trecho de busca).
        assertEquals(85.20, UberParser.parse(navegacao + "Aceitar", fromOcr = true)!!.price, eps)
    }

    @Test fun taxa_por_km_nunca_vira_preco() {
        // Preço coberto por outra janela: sobra só "Est. de R$1,50/km" → não inventa oferta de R$ 1,50.
        val semPreco = uberxTesseract.filterNot { it == "R$ 24,54" }
        assertNull(UberParser.parse(semPreco, fromOcr = true))
        assertTrue(UberParser.stillShows(semPreco, UberParser.parse(uberxTesseract, fromOcr = true)!!))
        assertFalse(UberParser.stillShows(comfortTesseract, UberParser.parse(uberxTesseract, fromOcr = true)!!))
        assertTrue(ParseUtils.isRate("Est. de R$1,50/km", "Est. de R$1,50".length))
        assertFalse(ParseUtils.isRate("R$ 18,40, 4 min (1,2 km)", "R$ 18,40".length))
    }

    // ------------------------------------------------------------------ ML Kit no aparelho (12/11/2026, 13:43)

    // Diagnóstico real: "1.6 km" da busca saiu "l6 km" e o "5" da viagem ganhou um ponto na frente.
    private val priorityMlKit = listOf(
        "Ginásio do", "Oswaldo", "040", "Jrtente", "CTL-Santa ls bel", "oneira", "UPA", "Ate",
        "2 Priority Exclusivo", "ue", "R$ 8,43", "Est. de R$1,96/km", "+10% de ganhos Uber Pro",
        "+R$ 196 incluída para retirada", "4 min l6 km", "R. Ângelo Silveira, Lomba do Sabão,", "Viamão",
        "* 4,92 (962)", ".5 minutos• 2.7 km", "Rua Dolores Duran, 240,", "Agronomia, Porto Alegre", "Aceitar",
    )

    @Test fun mlkit_busca_com_l_no_lugar_do_1_e_ponto_perdido() {
        val o = UberParser.parse(priorityMlKit, fromOcr = true)!!
        assertEquals(8.43, o.price, eps)
        assertEquals(4, o.pickupMin)
        assertEquals(1.6, o.pickupKm!!, eps)
        assertEquals(5, o.tripMin)
        assertEquals(2.7, o.tripKm!!, eps)
        assertEquals("R. Ângelo Silveira, Lomba do Sabão, Viamão", o.pickupAddress)
        assertEquals("Rua Dolores Duran, 240, Agronomia, Porto Alegre", o.dropoffAddress)
        // Mesmo quadro com "•" entre tempo e km.
        val comPonto = priorityMlKit.map { if (it == "4 min l6 km") "4 min•l6 km" else it }
        assertEquals(1.6, UberParser.parse(comPonto, fromOcr = true)!!.pickupKm!!, eps)
    }

    @Test fun mlkit_km_da_busca_ilegivel_vem_da_taxa_por_km() {
        // Busca sem km legível: 8,43 ÷ 1,96 = 4,3 km no total − 2,7 da viagem = 1,6.
        val ilegivel = priorityMlKit.map { if (it == "4 min l6 km") "4 min •" else it }
        val o = UberParser.parse(ilegivel, fromOcr = true)!!
        assertEquals(1.6, o.pickupKm!!, eps)
        assertEquals(2.7, o.tripKm!!, eps)
        assertEquals(1.96, ParseUtils.findPerKmRate(listOf("Est. de R$1,96/km"))!!, eps)
        assertNull(ParseUtils.findPerKmRate(listOf("R$ 8,43", "+R$ 1,96 incluída para retirada")))
    }

    // ------------------------------------------------------------------ limpeza de linha

    @Test fun limpa_erros_comuns_do_ocr() {
        assertEquals("6 min • 1.6km", ParseUtils.cleanOcrLine("6 min1.6km"))
        assertEquals("9 min • 3.9 km", ParseUtils.cleanOcrLine("9 min.3.9 km"))
        assertEquals("9 min • 3.9 km", ParseUtils.cleanOcrLine("(E) 9 min «3.9 km"))
        assertEquals("29 minutos • 14.8 km", ParseUtils.cleanOcrLine("[0] 29 minutos * 14.8 km"))
        assertEquals("1,4 km • 4 min", ParseUtils.cleanOcrLine("1,4 km.4 min"))
        assertEquals("4 min • 1.6 km", ParseUtils.cleanOcrLine("4 min•l6 km"))
        assertEquals("4 min • 1.6 km", ParseUtils.cleanOcrLine("4 min l6 km"))
        assertEquals("5 minutos • 2.7 km", ParseUtils.cleanOcrLine(".5 minutos• 2.7 km"))
        // Velocidade possível: não mexe.
        assertEquals("29 minutos • 14 km", ParseUtils.cleanOcrLine("29 minutos • 14 km"))
        assertEquals("1 h 5 min • 75 km", ParseUtils.cleanOcrLine("1 h 5 min • 75 km"))
        // Não mexe em quem não é tempo/distância com número colado.
        assertEquals("4 min (1,2 km) de distância", ParseUtils.cleanOcrLine("4 min (1,2 km) de distância"))
        assertEquals("0,8 km", ParseUtils.cleanOcrLine("0,8 km"))
        assertEquals("10 min", ParseUtils.cleanOcrLine("10 min"))
        assertEquals("Rua Atenas, 70, Morro Santana,", ParseUtils.cleanOcrLine("Rua Atenas, 70, Morro Santana,"))
    }

    @Test fun dinheiro_e_nota_com_erro_de_ocr() {
        assertEquals(24.54, ParseUtils.parseMoney("RS 24,54")!!, eps)
        assertNull(ParseUtils.parseMoney("Rodovia RS 040"))
        assertNull(ParseUtils.parseMoney("RS-118, 2500"))
        assertEquals(4.90, ParseUtils.parseRating("x 4,90 (989)")!!, eps)
        assertEquals(4.92, ParseUtils.parseRating("*x 4,92(640)")!!, eps)
        assertNull(ParseUtils.parseRating("R$ 4,50"))
    }

    @Test fun pedaco_de_endereco() {
        assertTrue(ParseUtils.isAddressPart("Sabão, Viamão"))
        assertTrue(ParseUtils.isAddressPart("Porto Alegre"))
        assertTrue(ParseUtils.isAddressPart("Av. Pixinguinha, 120"))
        assertTrue(ParseUtils.isAddressPart("Rua A"))
        assertFalse(ParseUtils.isAddressPart("» ECO"))
        assertFalse(ParseUtils.isAddressPart("te UR"))
        assertFalse(ParseUtils.isAddressPart("x 4,90 (989)"))
        assertFalse(ParseUtils.isAddressPart("+10% de ganhos Uber Pro"))
        assertFalse(ParseUtils.isAddressPart("Aceitar"))
    }

    // ------------------------------------------------------------------ ordem de leitura

    @Test fun layout_ordena_filtra_ruido_e_ignora_areas() {
        // Caixas reais (PP-OCR) do print UberX, fora de ordem, com ruído de ícones.
        val lines = listOf(
            OcrLine("Aceitar", 483, 2315, 675, 2369),
            OcrLine("Exclusivo", 415, 1103, 632, 1146),
            OcrLine("29 minutos·14.8km", 212, 1998, 776, 2051),
            OcrLine("Uberx", 196, 1102, 344, 1147),
            OcrLine("X", 960, 1120, 1010, 1170),
            OcrLine("R$", 88, 1199, 250, 1336),
            OcrLine("24,54", 270, 1199, 638, 1336),
            OcrLine("Est.deR$1,50/km", 100, 1366, 551, 1415),
            OcrLine("+10%deganhosuberPro", 197, 1464, 786, 1510),
            OcrLine("O", 100, 1630, 170, 1690),
            OcrLine("6 min1.6km", 212, 1627, 582, 1678),
            OcrLine("Sabao,Viamao", 212, 1774, 548, 1814),
            OcrLine("Av.Sen.Salgado Filho, Passo do", 210, 1711, 935, 1754),
            OcrLine("4,90 (989)", 304, 1858, 543, 1911),
            OcrLine("Branco,PortoAlegre", 209, 2144, 680, 2193),
            OcrLine("R.DonaLaura,320,BairroRio", 211, 2084, 872, 2124),
            OcrLine("M", 1000, 920, 1130, 1050),
            OcrLine("3Regimento de", 95, 708, 439, 752),
        )
        val texts = OcrLayout.toTexts(lines)
        assertEquals(
            listOf(
                "3Regimento de", "Uberx", "Exclusivo", "R$", "24,54", "Est.deR$1,50/km", "+10%deganhosuberPro",
                "6 min1.6km", "Av.Sen.Salgado Filho, Passo do", "Sabao,Viamao", "4,90 (989)", "29 minutos·14.8km",
                "R.DonaLaura,320,BairroRio", "Branco,PortoAlegre", "Aceitar",
            ),
            texts,
        )
        assertUberX(UberParser.parse(texts, fromOcr = true)!!)

        // Janela do próprio MApp cobrindo a faixa do preço: essas linhas somem e não há oferta.
        val coberto = OcrLayout.toTexts(lines, excluded = listOf(OcrBox(0, 1150, 1080, 1440)))
        assertFalse(coberto.any { it.contains("24,54") || it.contains("Est.") })
        assertNull(UberParser.parse(coberto, fromOcr = true))
    }
}
