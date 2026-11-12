package br.com.mapp.reader

import br.com.mapp.core.model.TripStage
import br.com.mapp.reader.parser.TripScreens
import br.com.mapp.reader.parser.TripStageTracker
import br.com.mapp.reader.parser.UberParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Telas da Uber depois da oferta (prints de 12/11/2026). */
class TripScreensTest {
    private val indoBuscar = listOf(
        "ua Monsenhor", "30", "LIMITE", "RUA MON", "Rua Boehmerwald", "2 min", "0,9 km", "Encontro com Caio Augusto",
    )
    private val avaliar = listOf("Como foi a viagem?", "Caio Augusto", "Avaliar usuário")

    @Test fun reconhece_aceite_e_fim() {
        assertEquals(TripStage.ACCEPTED, TripScreens.detect(indoBuscar))
        assertEquals(TripStage.FINISHED, TripScreens.detect(avaliar))
        // OCR sem espaços / sem acento
        assertEquals(TripStage.ACCEPTED, TripScreens.detect(listOf("2 min 0,9km", "EncontrocomCaio Augusto")))
        assertEquals(TripStage.FINISHED, TripScreens.detect(listOf("Comofoiaviagem?")))
        assertEquals(TripStage.FINISHED, TripScreens.detect(listOf("Avaliar usuario")))
    }

    @Test fun telas_comuns_nao_sao_etapa() {
        assertNull(TripScreens.detect(listOf("Página inicial", "R$ 0,00", "Você está online")))
        assertNull(TripScreens.detect(emptyList()))
        assertNull(
            TripScreens.detect(
                listOf("UberX", "R$ 18,40", "4 min (1,2 km) de distância", "Viagem: 22 min (12,5 km)", "Aceitar"),
            ),
        )
    }

    @Test fun tela_indo_buscar_nao_vira_oferta() {
        // "2 min · 0,9 km" sem preço: o parser não pode confundir com oferta.
        assertNull(UberParser.parse(indoBuscar))
        assertNull(UberParser.parse(indoBuscar, fromOcr = true))
    }

    @Test fun avisa_so_na_troca_de_etapa() {
        val t = TripStageTracker(holdMs = 20_000)
        assertEquals(TripStage.ACCEPTED, t.onScreen(TripStage.ACCEPTED, 0))
        assertNull(t.onScreen(TripStage.ACCEPTED, 1_000))
        assertNull(t.onScreen(null, 2_000))                 // quadro mal lido
        assertNull(t.onScreen(TripStage.ACCEPTED, 3_000))   // não repete
        assertNull(t.onScreen(null, 60_000))                // viagem em andamento (esqueceu a etapa)
        assertEquals(TripStage.FINISHED, t.onScreen(TripStage.FINISHED, 900_000))
        assertNull(t.onScreen(TripStage.FINISHED, 901_000))
        assertEquals(TripStage.ACCEPTED, t.onScreen(TripStage.ACCEPTED, 1_000_000)) // próxima corrida
    }
}
