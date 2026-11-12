package br.com.mapp.reader

import br.com.mapp.core.model.Platform
import br.com.mapp.reader.parser.InDriveParser
import br.com.mapp.reader.parser.NinetyNineParser
import br.com.mapp.reader.parser.OfferParsers
import br.com.mapp.reader.parser.UberParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amostras INVENTADAS (plausíveis). Ao capturar textos reais com debugCapture, adicione cada um
 * como novo caso aqui, com o nome da versão do app.
 */
class OfferParsersTest {
    private val eps = 1e-9

    // ------------------------------------------------------------------ Uber

    @Test fun uber_oferta_completa() {
        val texts = listOf(
            "UberX", "R$ 18,40", "★ 4,92", "Verificado",
            "4 min (1,2 km) de distância", "Rua Augusta, 1500 - Consolação",
            "Viagem: 22 min (12,5 km)", "Av. Paulista, 900 - Bela Vista",
            "Aceitar",
        )
        val o = UberParser.parse(texts)!!
        assertEquals(Platform.UBER, o.platform)
        assertEquals(18.40, o.price, eps)
        assertEquals(4, o.pickupMin)
        assertEquals(1.2, o.pickupKm!!, eps)
        assertEquals(22, o.tripMin)
        assertEquals(12.5, o.tripKm!!, eps)
        assertEquals("Rua Augusta, 1500 - Consolação", o.pickupAddress)
        assertEquals("Av. Paulista, 900 - Bela Vista", o.dropoffAddress)
        assertEquals(4.92, o.passengerRating!!, eps)
        assertTrue(o.rawText.contains("UberX"))
    }

    @Test fun uber_dinamico_nao_vira_preco() {
        val texts = listOf(
            "Comfort", "Inclui R$ 3,20 de Dinâmico", "R$ 27,90", "4,87 ★",
            "6 min (2,3 km) de distância", "Viagem: 1 h 5 min (45,2 km)",
            "R. Vergueiro, 3000 - Vila Mariana", "Aeroporto de Congonhas - Campo Belo",
        )
        val o = UberParser.parse(texts)!!
        assertEquals(27.90, o.price, eps)
        assertEquals(6, o.pickupMin)
        assertEquals(65, o.tripMin)
        assertEquals(45.2, o.tripKm!!, eps)
        assertEquals("R. Vergueiro, 3000 - Vila Mariana", o.pickupAddress)
        assertEquals("Aeroporto de Congonhas - Campo Belo", o.dropoffAddress)
    }

    @Test fun uber_pares_sem_rotulo_usam_ordem() {
        val texts = listOf(
            "UberX", "R$12.50", "3 min (850 m)", "Rua das Flores, 45 - Centro",
            "15 min (7,8 km)", "Av. Brasil, 2000 - Jardim América",
        )
        val o = UberParser.parse(texts)!!
        assertEquals(12.50, o.price, eps)
        assertEquals(3, o.pickupMin)
        assertEquals(0.85, o.pickupKm!!, eps)
        assertEquals(15, o.tripMin)
        assertEquals(7.8, o.tripKm!!, eps)
        assertEquals("Rua das Flores, 45 - Centro", o.pickupAddress)
        assertEquals("Av. Brasil, 2000 - Jardim América", o.dropoffAddress)
    }

    @Test fun uber_texto_unico_com_descricao_do_cartao() {
        // contentDescription do cartão inteiro + textos dos filhos repetidos.
        val texts = listOf(
            "UberX, R$ 18,40, 4 min (1,2 km) de distância, Viagem: 22 min (12,5 km)",
            "UberX", "R$ 18,40", "4 min (1,2 km) de distância", "Viagem: 22 min (12,5 km)",
        )
        val o = UberParser.parse(texts)!!
        assertEquals(18.40, o.price, eps)
        assertEquals(4, o.pickupMin)
        assertEquals(1.2, o.pickupKm!!, eps)
        assertEquals(22, o.tripMin)
        assertEquals(12.5, o.tripKm!!, eps)
    }

    @Test fun uber_rotulos_em_linhas_separadas() {
        val texts = listOf(
            "R$ 21,00", "5 min (1,9 km)", "de distância", "Rua Tamandaré, 200",
            "Viagem", "19 min (9,1 km)", "Rua Domingos de Morais, 1000",
        )
        val o = UberParser.parse(texts)!!
        assertEquals(5, o.pickupMin)
        assertEquals(19, o.tripMin)
        assertEquals("Rua Tamandaré, 200", o.pickupAddress)
        assertEquals("Rua Domingos de Morais, 1000", o.dropoffAddress)
    }

    @Test fun uber_tela_de_ganhos_nao_e_oferta() {
        assertNull(UberParser.parse(listOf("Ganhos de hoje", "R$ 152,30", "Você está online")))
    }

    // Textos REAIS (prints de 12/11/2026, Uber Driver, Viamão/Porto Alegre).
    private val uberTelaInicial = listOf(
        "R$ 0,00", "+R$ 4", "1-3 min", "+R$ 4", "Procurando viagens",
    )
    private val uberCartaoReal = listOf(
        "Comfort", "Exclusivo", "R$ 14,41", "Est. de R$1,40/km", "+10% de ganhos Uber Pro",
        "9 min • 3.9 km", "R. Araras, Parque Indio Jari, Viamão", "4,92 (640)",
        "16 minutos • 6.4 km", "Rua Atenas, 70, Morro Santana, Porto Alegre", "Aceitar",
    )

    @Test fun uber_tela_inicial_sem_chamada_nao_e_oferta() {
        assertNull(UberParser.parse(uberTelaInicial))
        assertNull(UberParser.parse(listOf("R$ 85,20", "+R$ 4", "1-3 min", "Procurando viagens")))
    }

    private fun assertCartaoReal(texts: List<String>) {
        val o = UberParser.parse(texts)!!
        assertEquals(14.41, o.price, eps)
        assertEquals(9, o.pickupMin)
        assertEquals(3.9, o.pickupKm!!, eps)
        assertEquals(16, o.tripMin)
        assertEquals(6.4, o.tripKm!!, eps)
        assertEquals("R. Araras, Parque Indio Jari, Viamão", o.pickupAddress)
        assertEquals("Rua Atenas, 70, Morro Santana, Porto Alegre", o.dropoffAddress)
        assertEquals(4.92, o.passengerRating!!, eps)
    }

    @Test fun uber_cartao_real_sozinho() = assertCartaoReal(uberCartaoReal)

    @Test fun uber_cartao_real_por_cima_da_tela_inicial() {
        assertCartaoReal(uberTelaInicial + uberCartaoReal)
        assertCartaoReal(listOf("R$ 85,20", "+R$ 4", "1-3 min") + uberCartaoReal + listOf("+R$ 6", "2-4 min"))
    }

    @Test fun uber_cartao_real_uberx_tela_cheia() {
        val o = UberParser.parse(listOf(
            "UberX", "Exclusivo", "R$ 24,54", "Est. de R$1,50/km", "+10% de ganhos Uber Pro",
            "6 min • 1.6 km", "Av. Sen. Salgado Filho, Passo do Sabão, Viamão", "4,90 (989)",
            "29 minutos • 14.8 km", "R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", "Aceitar",
        ))!!
        assertEquals(24.54, o.price, eps)
        assertEquals(6, o.pickupMin)
        assertEquals(1.6, o.pickupKm!!, eps)
        assertEquals(29, o.tripMin)
        assertEquals(14.8, o.tripKm!!, eps)
        assertEquals("Av. Sen. Salgado Filho, Passo do Sabão, Viamão", o.pickupAddress)
        assertEquals("R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", o.dropoffAddress)
    }

    @Test fun uber_textos_em_pedacos() {
        // Mesmo cartão com moeda, tempo, separador e km em nós separados.
        val o = UberParser.parse(listOf(
            "UberX", "R$", "24,54", "6 min", "•", "1.6 km", "Av. Sen. Salgado Filho, Passo do Sabão, Viamão",
            "29 minutos", "•", "14.8 km", "R. Dona Laura, 320, Bairro Rio Branco, Porto Alegre", "Aceitar",
        ))!!
        assertEquals(24.54, o.price, eps)
        assertEquals(6, o.pickupMin)
        assertEquals(1.6, o.pickupKm!!, eps)
        assertEquals(29, o.tripMin)
        assertEquals(14.8, o.tripKm!!, eps)
    }

    @Test fun sem_valor_nao_e_oferta() {
        assertNull(UberParser.parse(listOf("UberX", "4 min (1,2 km) de distância", "Rua A, 10 - Centro")))
        assertNull(UberParser.parse(emptyList()))
    }

    // ------------------------------------------------------------------ 99

    @Test fun noventa_e_nove_oferta_com_rotulos() {
        val texts = listOf(
            "99Pop", "R$ 14,90", "Dinheiro", "★ 4,95",
            "Embarque: 3 min · 0,9 km", "Rua das Palmeiras, 45 - Centro",
            "Corrida: 18 min · 9,4 km", "Av. Brasil, 2000 - Jardim América",
            "Aceitar corrida",
        )
        val o = NinetyNineParser.parse(texts)!!
        assertEquals(Platform.NINETY_NINE, o.platform)
        assertEquals(14.90, o.price, eps)
        assertEquals(3, o.pickupMin)
        assertEquals(0.9, o.pickupKm!!, eps)
        assertEquals(18, o.tripMin)
        assertEquals(9.4, o.tripKm!!, eps)
        assertEquals("Rua das Palmeiras, 45 - Centro", o.pickupAddress)
        assertEquals("Av. Brasil, 2000 - Jardim América", o.dropoffAddress)
        assertEquals(4.95, o.passengerRating!!, eps)
    }

    @Test fun noventa_e_nove_km_antes_do_tempo_e_enderecos_rotulados() {
        val texts = listOf(
            "99Comfort", "R$ 1.034,50", "1,1 km · 4 min até o passageiro",
            "Origem: Rua XV de Novembro, 300", "Destino: Estrada da Graciosa, km 8",
            "Viagem 1h 20min · 62 km", "4,90",
        )
        val o = NinetyNineParser.parse(texts)!!
        assertEquals(1034.50, o.price, eps)
        assertEquals(4, o.pickupMin)
        assertEquals(1.1, o.pickupKm!!, eps)
        assertEquals(80, o.tripMin)
        assertEquals(62.0, o.tripKm!!, eps)
        assertEquals("Rua XV de Novembro, 300", o.pickupAddress)
        assertEquals("Estrada da Graciosa, km 8", o.dropoffAddress)
        assertEquals(4.90, o.passengerRating!!, eps)
    }

    // ------------------------------------------------------------------ inDrive

    @Test fun indrive_enderecos_antes_das_distancias_e_contrapropostas() {
        val texts = listOf(
            "Carlos", "★ 4,87 (152)", "há 1 min", "R$ 22",
            "Rua Augusta, 1500 - Consolação", "Av. Paulista, 900 - Bela Vista",
            "1,4 km · 4 min", "Viagem: 8,7 km · 21 min",
            "Aceitar por R$ 22", "Oferecer seu preço", "R$ 24", "R$ 26",
        )
        val o = InDriveParser.parse(texts)!!
        assertEquals(Platform.INDRIVE, o.platform)
        assertEquals(22.0, o.price, eps)
        assertEquals(4, o.pickupMin)
        assertEquals(1.4, o.pickupKm!!, eps)
        assertEquals(21, o.tripMin)
        assertEquals(8.7, o.tripKm!!, eps)
        assertEquals("Rua Augusta, 1500 - Consolação", o.pickupAddress)
        assertEquals("Av. Paulista, 900 - Bela Vista", o.dropoffAddress)
        assertEquals(4.87, o.passengerRating!!, eps)
    }

    @Test fun indrive_tempo_e_distancia_em_linhas_separadas() {
        val texts = listOf(
            "R$ 15", "3 min", "1,2 km", "de você",
            "Rua Sete de Setembro, 80", "Praça da Sé - Centro",
        )
        val o = InDriveParser.parse(texts)!!
        assertEquals(15.0, o.price, eps)
        assertEquals(3, o.pickupMin)
        assertEquals(1.2, o.pickupKm!!, eps)
        assertNull(o.tripKm)
        assertNull(o.tripMin)
        assertEquals("Rua Sete de Setembro, 80", o.pickupAddress)
        assertEquals("Praça da Sé - Centro", o.dropoffAddress)
    }

    @Test fun indrive_so_enderecos_sem_distancia_ainda_e_oferta() {
        val o = InDriveParser.parse(listOf("Ana", "R$ 30", "Rua Bahia, 10 - Funcionários", "Av. Afonso Pena, 1000"))
        assertNotNull(o)
        assertEquals("Rua Bahia, 10 - Funcionários", o!!.pickupAddress)
        assertEquals("Av. Afonso Pena, 1000", o.dropoffAddress)
        assertNull(o.tripKm)
    }

    // ------------------------------------------------------------------ geral

    @Test fun um_unico_par_sem_rotulo_e_viagem() {
        val o = UberParser.parse(listOf("R$ 9,80", "12 min (5,0 km)", "Rua Chile, 50 - Comércio"))!!
        assertEquals(12, o.tripMin)
        assertEquals(5.0, o.tripKm!!, eps)
        assertNull(o.pickupMin)
    }

    @Test fun um_par_de_viagem_e_endereco_depois_dele_e_destino() {
        val o = UberParser.parse(
            listOf("R$ 9,80", "2 min (0,5 km) de distância", "Viagem: 12 min (5,0 km)", "Rua Chile, 50 - Comércio"),
        )!!
        assertNull(o.pickupAddress)
        assertEquals("Rua Chile, 50 - Comércio", o.dropoffAddress)
    }

    @Test fun parser_por_plataforma() {
        assertSame(UberParser, OfferParsers.forPlatform(Platform.UBER))
        assertSame(NinetyNineParser, OfferParsers.forPlatform(Platform.NINETY_NINE))
        assertSame(InDriveParser, OfferParsers.forPlatform(Platform.INDRIVE))
        Platform.entries.forEach { assertEquals(it, OfferParsers.forPlatform(it).platform) }
    }

    // ------------------------------------------------------------------ tipo da corrida (jornada)

    @Test fun tipo_da_corrida_por_plataforma() {
        val uber = listOf(
            "UberX", "R$ 18,40", "4 min (1,2 km) de distância", "Rua A, 10 - Centro",
            "Viagem: 22 min (12,5 km)", "Av. B, 900 - Bela Vista", "Aceitar",
        )
        assertEquals("UberX", UberParser.parse(uber)!!.category)
        assertEquals("Black", UberParser.parse(listOf("Uber Black") + uber.drop(1))!!.category)
        assertEquals("Elétrico", UberParser.parse(listOf("Elétrico") + uber.drop(1))!!.category)
        assertEquals("Comfort", UberParser.parse(listOf("UberX", "Comfort", "Exclusivo") + uber.drop(1))!!.category)
        assertNull(UberParser.parse(uber.drop(1))!!.category)

        val pop = listOf(
            "99Pop", "R$ 14,90", "Embarque: 3 min · 0,9 km", "Rua C, 45 - Centro",
            "Corrida: 18 min · 9,4 km", "Av. D, 2000 - Jardim", "Aceitar corrida",
        )
        assertEquals("Pop", NinetyNineParser.parse(pop)!!.category)
        assertEquals("Comfort", NinetyNineParser.parse(listOf("99Comfort") + pop.drop(1))!!.category)
    }

    @Test fun texto_longo_nao_vira_tipo() {
        assertNull(UberParser.categoryOf("Uber Black é a categoria premium da cidade"))
        assertNull(UberParser.categoryOf("Exclusivo"))
        assertEquals("UberX", UberParser.categoryOf("Uberx"))
        assertEquals("UberX", UberParser.categoryOf("Uber X"))
    }
}
