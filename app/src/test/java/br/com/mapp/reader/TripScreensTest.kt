package br.com.mapp.reader

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.TripEvent
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.shift.TripMatcher
import br.com.mapp.reader.parser.TripScreens
import br.com.mapp.reader.parser.TripStageTracker
import br.com.mapp.reader.parser.UberParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telas da Uber depois da oferta (prints de 12 e 13/11/2026). */
class TripScreensTest {
    private val indoBuscar = listOf(
        "ua Monsenhor", "30", "LIMITE", "RUA MON", "Rua Boehmerwald", "2 min", "0,9 km", "Encontro com Caio Augusto",
    )
    private val avaliar = listOf("Como foi a viagem?", "Caio Augusto", "Avaliar usuário")

    /** Print de 13/11: passageira a bordo, mapa até o destino. */
    private val destinoDeAna = listOf(
        "Internacional", "Salgado Filho", "(POA)", "R-SUL", "40", "LIMITE", "Navegar", "29 min", "18,6 km", "Destino de Ana",
    )

    private fun accepted(name: String? = null, km: Double? = null) = TripEvent(TripStage.ACCEPTED, name, km)
    private fun onTrip(name: String, km: Double? = null) = TripEvent(TripStage.ON_TRIP, name, km)
    private val finished = TripEvent(TripStage.FINISHED)
    private val idle = TripEvent(TripStage.IDLE)

    // ------------------------------------------------------------------ leitura das telas

    @Test fun reconhece_aceite_viagem_e_fim_com_nome() {
        assertEquals(accepted("caio", 0.9), TripScreens.detect(indoBuscar))
        assertEquals(onTrip("ana", 18.6), TripScreens.detect(destinoDeAna))
        assertEquals(finished, TripScreens.detect(avaliar))
        // Nome na linha de baixo
        assertEquals(onTrip("joao", 4.1), TripScreens.detect(listOf("12 min", "4,1 km", "Destino de", "João Pedro")))
        assertEquals(accepted("bia"), TripScreens.detect(listOf("Encontro com", "Bia")))
    }

    @Test fun ocr_colado_ou_sem_acento() {
        // Colado: ainda é aceite (sem nome; o "Destino de" depois traz o nome).
        assertEquals(accepted(km = 0.9), TripScreens.detect(listOf("2 min 0,9km", "EncontrocomCaio Augusto")))
        assertEquals(finished, TripScreens.detect(listOf("Comofoiaviagem?")))
        assertEquals(finished, TripScreens.detect(listOf("Avaliar usuario")))
        assertEquals(onTrip("ana"), TripScreens.detect(listOf("Destinode Ana")))
    }

    @Test fun tela_inicial_so_pela_imagem() {
        // Print de 12/11: tela inicial da Uber esperando chamada.
        val inicial = listOf("R$ 0,00", "+R$ 4", "1-3 min", "+R$ 4", "Procurando viagens")
        assertEquals(idle, TripScreens.detect(inicial, fromOcr = true))
        assertEquals(idle, TripScreens.detect(listOf("Procurandoviagens"), fromOcr = true))
        // Texto da Acessibilidade mistura a tela de fundo: não vale.
        assertNull(TripScreens.detect(inicial))
        // Etapa da corrida na mesma leitura ganha da tela inicial.
        assertEquals(onTrip("ana", 18.6), TripScreens.detect(destinoDeAna + "Procurando viagens", fromOcr = true))
    }

    @Test fun telas_comuns_nao_sao_etapa() {
        assertNull(TripScreens.detect(listOf("Página inicial", "R$ 0,00", "Você está online")))
        assertNull(TripScreens.detect(emptyList()))
        assertNull(TripScreens.detect(listOf("Destino definido", "Rua A, 10")))
        assertNull(
            TripScreens.detect(
                listOf("UberX", "R$ 18,40", "4 min (1,2 km) de distância", "Viagem: 22 min (12,5 km)", "Aceitar"),
            ),
        )
    }

    @Test fun telas_de_corrida_nao_viram_oferta() {
        // "2 min · 0,9 km" / "29 min · 18,6 km" sem preço: o parser não pode confundir com oferta.
        assertNull(UberParser.parse(indoBuscar))
        assertNull(UberParser.parse(indoBuscar, fromOcr = true))
        assertNull(UberParser.parse(destinoDeAna))
        assertNull(UberParser.parse(destinoDeAna, fromOcr = true))
    }

    // ------------------------------------------------------------------ eventos únicos

    @Test fun avisa_so_na_troca_e_depois_de_duas_leituras() {
        val t = TripStageTracker(holdMs = 20_000, confirm = 2)
        assertNull(t.onScreen(accepted("caio"), 0))
        assertEquals(accepted("caio"), t.onScreen(accepted("caio"), 1_000))
        assertNull(t.onScreen(accepted("caio"), 2_000))
        assertNull(t.onScreen(null, 3_000))                     // quadro mal lido
        assertNull(t.onScreen(accepted(), 4_000))                // mesmo aceite sem nome
        assertNull(t.onScreen(onTrip("caio"), 60_000))
        assertEquals(onTrip("caio"), t.onScreen(onTrip("caio"), 61_000))
        assertNull(t.onScreen(onTrip("caio"), 62_000))
        assertEquals(finished, t.onScreen(finished, 900_000))   // fim: uma leitura basta
        assertNull(t.onScreen(finished, 901_000))
        assertNull(t.onScreen(accepted("ana"), 1_000_000))
        assertEquals(accepted("ana"), t.onScreen(accepted("ana"), 1_001_000))
    }

    @Test fun nome_lido_errado_uma_vez_nao_vira_corrida() {
        val t = TripStageTracker(holdMs = 20_000, confirm = 2)
        t.onScreen(onTrip("ana"), 0)
        assertEquals(onTrip("ana"), t.onScreen(onTrip("ana"), 1_000))
        assertNull(t.onScreen(onTrip("aria"), 2_000))            // OCR leu errado
        assertNull(t.onScreen(onTrip("ana"), 3_000))
        assertNull(t.onScreen(onTrip("ana"), 4_000))
    }

    @Test fun aceite_rapido_lido_uma_vez_vale_quando_some_da_tela() {
        // Aceitou e o Waze abriu na hora: "Encontro com Caio" ficou em 1 quadro só.
        val t = TripStageTracker(holdMs = 20_000, confirm = 2)
        assertNull(t.onScreen(accepted("caio", 1.2), 0))
        assertTrue(t.hasPending)
        assertEquals(listOf(accepted("caio", 1.2)), t.read(null, 1_500))  // app saiu da frente
        assertFalse(t.hasPending)
        // Embarque rápido: "Destino de Caio" uma vez e já a avaliação → os dois valem, em ordem.
        assertNull(t.onScreen(onTrip("caio"), 600_000))
        assertEquals(listOf(onTrip("caio"), finished), t.read(finished, 600_800))
    }

    @Test fun nome_diferente_na_mesma_etapa_lido_uma_vez_nao_vale() {
        val t = TripStageTracker(holdMs = 20_000, confirm = 2)
        t.onScreen(onTrip("ana"), 0)
        t.onScreen(onTrip("ana"), 1_000)
        assertNull(t.onScreen(onTrip("bruno"), 2_000))
        assertTrue(t.read(null, 3_000).isEmpty())                   // sumiu: era erro de leitura
    }

    @Test fun tela_inicial_precisa_de_duas_leituras_e_repete() {
        val t = TripStageTracker(holdMs = 20_000, confirm = 2, idleRepeatMs = 30_000)
        assertNull(t.onScreen(idle, 0))
        assertTrue(t.read(null, 500).isEmpty())                     // um quadro só: transição, não vale
        assertNull(t.onScreen(idle, 1_000))
        assertEquals(idle, t.onScreen(idle, 2_000))
        assertNull(t.onScreen(idle, 10_000))
        assertNull(t.onScreen(idle, 25_000))
        assertEquals(idle, t.onScreen(idle, 33_000))                // ainda na tela inicial: avisa de novo
    }

    @Test fun mesma_etapa_depois_de_um_tempo_sem_leitura_avisa_de_novo() {
        // Waze na frente durante a viagem: nenhuma leitura; o próximo "Encontro com" avisa de novo.
        val t = TripStageTracker(holdMs = 20_000, confirm = 1)
        assertEquals(accepted(), t.onScreen(accepted(), 0))
        assertNull(t.onScreen(accepted(), 10_000))
        assertEquals(accepted(), t.onScreen(accepted(), 30 * 60_000L))
    }

    // ------------------------------------------------------------------ qual oferta foi aceita

    private val min = 60_000L

    @Test fun corrida_normal_conta_uma_vez() {
        val m = TripMatcher<String>()
        m.offerSeen("oferta1", 0)
        assertEquals("oferta1", m.onStage(accepted("caio"), 10_000).count)
        m.markCounted("oferta1")
        assertNull(m.onStage(onTrip("caio"), 5 * min).count)      // mesmo passageiro
        assertNull(m.onStage(finished, 20 * min).count)
    }

    @Test fun corrida_aceita_durante_outra_viagem_conta_quando_o_novo_nome_aparece() {
        // Print de 13/11: "Destino de Ana" na tela; oferta nova aceita no meio da viagem; a Uber só
        // mostra "Encontro com Bruno" depois que a Ana desce (bem mais de 90 s depois da oferta).
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        assertEquals("ana", m.onStage(accepted("ana"), 5_000).count)
        m.markCounted("ana")
        assertNull(m.onStage(onTrip("ana"), 6 * min).count)
        m.offerSeen("bruno", 20 * min)                             // oferta no meio da viagem
        assertNull(m.onStage(onTrip("ana"), 21 * min).count)        // tela continua "Destino de Ana"
        assertNull(m.onStage(finished, 29 * min).count)
        val r = m.onStage(accepted("bruno"), 30 * min)              // 10 min depois da oferta
        assertEquals("bruno", r.count)
        assertTrue(r.newRide)
    }

    @Test fun corrida_em_fila_conta_mesmo_sem_ver_o_fim_da_anterior() {
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 10 * min)
        // "Como foi a viagem?" não foi lido (Waze na frente): o nome novo basta.
        assertEquals("bruno", m.onStage(onTrip("bruno"), 40 * min).count)
    }

    @Test fun oferta_recusada_nao_conta() {
        val m = TripMatcher<String>()
        m.offerSeen("velha", 0)
        m.onStage(idle, 20_000)                                     // voltou a procurar: recusada
        val r = m.onStage(accepted("caio"), 10 * min)
        assertNull(r.count)
        assertTrue(r.newRide)
        assertNull(m.onStage(accepted(), 10 * min).count)
    }

    @Test fun aceite_rapido_nao_lido_conta_no_embarque() {
        // Aceitou em 1 s e o Waze abriu: "Encontro com" nunca foi lido; "Destino de Caio" 9 min depois.
        val m = TripMatcher<String>()
        m.offerSeen("o1", 0, pickupKm = 1.3, tripKm = 3.4, expectedMs = 14 * min)
        val r = m.onStage(onTrip("caio", km = 3.3), 9 * min)
        assertEquals("o1", r.count)
        assertTrue(r.started)
        // Distância da tela muito maior que a da oferta: era outra corrida (oferta não lida).
        val m2 = TripMatcher<String>()
        m2.offerSeen("o1", 0, pickupKm = 1.3, tripKm = 3.4)
        assertNull(m2.onStage(onTrip("caio", km = 18.6), 9 * min).count)
        // Passou do tempo de busca: não conta.
        val m3 = TripMatcher<String>()
        m3.offerSeen("o1", 0)
        assertNull(m3.onStage(onTrip("caio"), 30 * min).count)
    }

    @Test fun fim_nao_lido_fecha_quando_aparece_outro_passageiro() {
        // Avaliou rápido: "Como foi a viagem?" não foi lido; o próximo "Encontro com" fecha a Ana.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 30 * min)
        val r = m.onStage(accepted("bruno"), 30 * min + 5_000)
        assertEquals(1, r.finishedUnseen)
        assertFalse(r.cancelledPickup)
        assertEquals("bruno", r.count)
        assertEquals(1, m.openRides)
    }

    @Test fun avaliacao_atrasada_da_anterior_nao_fecha_a_corrida_da_fila() {
        // 15/11: corrida aceita durante outra viagem. Na troca a Uber mostrou "Encontro com Bruno" e só
        // depois "Como foi a viagem?" da Ana: o Bruno saía da jornada (ícone laranja a viagem toda) e o
        // fim real virava "não vi o aceite".
        fun fila(): TripMatcher<String> {
            val m = TripMatcher<String>()
            m.offerSeen("ana", 0, expectedMs = 20 * min)
            m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
            m.onStage(onTrip("ana"), 5 * min)
            m.offerSeen("bruno", 15 * min, expectedMs = 25 * min, pickupMin = 7)
            val troca = m.onStage(accepted("bruno"), 25 * min)
            assertEquals("bruno", troca.count); m.markCounted("bruno")
            assertEquals(1, troca.finishedUnseen)                     // Ana finalizada na troca
            return m
        }
        val m = fila()
        assertTrue(m.onStage(finished, 25 * min + 20_000).lateFinish) // avaliação da Ana
        assertEquals(1, m.openRides)                                   // Bruno continua aberto
        assertNull(m.onStage(accepted("bruno"), 26 * min).count)
        m.onStage(onTrip("bruno"), 32 * min)
        val fim = m.onStage(finished, 55 * min)                        // fim real do Bruno
        assertFalse(fim.lateFinish)
        assertEquals(0, m.openRides)

        // Avaliação da Ana só com o Bruno já a bordo: também não fecha o Bruno.
        val b = fila()
        b.onStage(onTrip("bruno"), 31 * min)
        assertTrue(b.onStage(finished, 33 * min).lateFinish)
        assertEquals(1, b.openRides)
        // Memória salva e reaberta (Android matou o copiloto) mantém a avaliação devida.
        val c = fila()
        val reaberto = TripMatcher<String>()
        assertTrue(reaberto.restore(c.snapshot(25 * min + 5_000) { it }, 25 * min + 10_000) { it })
        assertTrue(reaberto.onStage(finished, 25 * min + 30_000).lateFinish)
        assertEquals(1, reaberto.openRides)

        // Sem a avaliação atrasada, o fim do Bruno fecha normalmente (a devida não atrapalha).
        val d = fila()
        d.onStage(onTrip("bruno"), 32 * min)
        assertFalse(d.onStage(finished, 55 * min).lateFinish)
        assertEquals(0, d.openRides)
        // Corrida que já embarcou e não tem nada devido: o fim fecha na hora.
        val e = TripMatcher<String>()
        e.offerSeen("o1", 0, expectedMs = 30 * min)
        e.onStage(accepted("caio"), 5_000); e.markCounted("o1")
        e.onStage(onTrip("caio"), 4 * min)
        assertFalse(e.onStage(finished, 9 * min).lateFinish)
        assertEquals(0, e.openRides)
    }

    @Test fun avaliacao_da_anterior_fora_da_memoria_nao_fecha_a_corrida_nova() {
        // 15/11 20:13: a corrida anterior já tinha se perdido (versão antiga) e o copiloto foi reiniciado
        // pela atualização no meio dela — a memória não tinha nada aberto. Oferta aceita durante ela;
        // na troca "Encontro com Rafaela" conta, e o "Como foi a viagem?" da anterior chega depois.
        val m = TripMatcher<String>()
        m.offerSeen("eletrico", 0, pickupKm = 1.8, tripKm = 11.3, expectedMs = 32 * min, pickupMin = 6)
        val troca = m.onStage(accepted("rafaela", km = 1.8), 8 * min)
        assertEquals("eletrico", troca.count); m.markCounted("eletrico")
        val avaliacao = m.onStage(finished, 8 * min + 15_000)
        assertTrue(avaliacao.lateFinish)                               // não fecha a Rafaela
        assertEquals(1, m.openRides)
        assertNull(m.onStage(accepted("rafaela"), 9 * min).count)      // continua a mesma corrida
        m.onStage(onTrip("rafaela", km = 11.3), 14 * min)
        assertFalse(m.onStage(finished, 38 * min).lateFinish)          // fim real
        assertEquals(0, m.openRides)

        // Se o embarque nunca for lido (Waze na frente) e a Uber voltar a procurar, conta como
        // finalizada — não cancelada — porque a tela de fim já tinha aparecido com ela aberta.
        val w = TripMatcher<String>()
        w.offerSeen("o1", 0, expectedMs = 40 * min)
        w.onStage(accepted("caio"), 5_000); w.markCounted("o1")
        assertTrue(w.onStage(finished, 3 * min).lateFinish)
        val volta = w.onStage(idle, 10 * min)
        assertEquals(1, volta.finishedUnseen)
        assertFalse(volta.cancelledPickup)
        // A marca sobrevive ao copiloto reaberto.
        val x = TripMatcher<String>()
        x.offerSeen("o1", 0, expectedMs = 40 * min)
        x.onStage(accepted("caio"), 5_000); x.markCounted("o1")
        x.onStage(finished, 3 * min)
        val y = TripMatcher<String>()
        assertTrue(y.restore(x.snapshot(3 * min) { it }, 4 * min) { it })
        assertEquals(1, y.onStage(idle, 10 * min).finishedUnseen)
    }

    @Test fun proximo_passageiro_mostrado_antes_do_fim_nao_perde_a_corrida_da_fila() {
        // "Encontro com Bruno" lido com a Ana ainda a bordo (logo depois de aceitar na fila); a Ana só
        // desce 14 min depois — mais que ¾ da corrida curta do Bruno. A tela de fim é da Ana.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 40 * min)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 10 * min, expectedMs = 12 * min)
        assertEquals("bruno", m.onStage(accepted("bruno"), 11 * min).count); m.markCounted("bruno")
        assertTrue(m.onStage(finished, 25 * min).lateFinish)
        assertEquals(1, m.openRides)
        m.onStage(onTrip("bruno"), 30 * min)
        assertFalse(m.onStage(finished, 41 * min).lateFinish)          // fim do Bruno
        assertEquals(0, m.openRides)
    }

    @Test fun tela_de_fim_repetida_nao_fecha_a_corrida_seguinte() {
        // A avaliação da Ana continua aparecendo (cartão na tela / texto de fundo) durante a busca e
        // até depois do embarque do Bruno.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 25 * min)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 20 * min, expectedMs = 20 * min)
        assertFalse(m.onStage(finished, 29 * min).lateFinish)          // fim da Ana
        assertEquals("bruno", m.onStage(accepted("bruno"), 29 * min + 10_000).count); m.markCounted("bruno")
        assertTrue(m.onStage(finished, 29 * min + 20_000).lateFinish)
        assertTrue(m.onStage(finished, 31 * min).lateFinish)
        m.onStage(onTrip("bruno"), 35 * min)
        assertTrue(m.onStage(finished, 35 * min + 10_000).lateFinish)  // a bordo há 10 s: não é o fim dele
        assertEquals(1, m.openRides)
        assertFalse(m.onStage(finished, 56 * min).lateFinish)          // fim de verdade
        assertEquals(0, m.openRides)
    }

    @Test fun passageiro_a_bordo_e_da_corrida_aberta_sem_nome_nao_da_oferta_nova() {
        // Aceite lido sem nome e embarque não lido (Waze). Oferta do Bruno aceita na fila; 4 min depois
        // "Destino de Ana" aparece pela 1ª vez: a Ana é da corrida aberta, o Bruno fica na fila.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 20 * min)
        assertEquals("ana", m.onStage(accepted(), 5_000).count); m.markCounted("ana")
        m.offerSeen("bruno", 6 * min, pickupKm = 1.5, pickupMin = 4)
        val bordo = m.onStage(onTrip("ana"), 10 * min)
        assertNull(bordo.count)
        assertFalse(bordo.cancelledPickup)
        assertTrue(bordo.started)
        assertEquals(1, m.openRides)
        assertFalse(m.onStage(finished, 18 * min).lateFinish)          // fim da Ana
        assertEquals("bruno", m.onStage(accepted("bruno", km = 1.4), 45 * min).count) // fila: vale depois de 20 min
    }

    @Test fun passageiro_a_bordo_perto_do_destino_nao_e_da_oferta_que_acabou_de_chegar() {
        // O MApp não viu a corrida da Ana. Oferta do Bruno (11,3 km) há 4 min; "Destino de Ana" com
        // 2 km pela frente: não dá para ter buscado e rodado 9 km — o Bruno fica na fila.
        val m = TripMatcher<String>()
        m.offerSeen("bruno", 0, pickupKm = 1.8, tripKm = 11.3, pickupMin = 6, expectedMs = 32 * min)
        assertNull(m.onStage(onTrip("ana", km = 2.0), 4 * min).count)
        assertEquals("bruno", m.onStage(accepted("bruno", km = 1.8), 30 * min).count)
        // Com os km da viagem inteira pela frente é o embarque dele (aceite e busca não lidos).
        val m2 = TripMatcher<String>()
        m2.offerSeen("bruno", 0, pickupKm = 1.8, tripKm = 11.3, pickupMin = 6)
        assertEquals("bruno", m2.onStage(onTrip("bruno", km = 11.1), 7 * min).count)
    }

    @Test fun km_da_tela_e_o_que_esta_junto_da_frase() {
        // Print de 15/11 (navegação da Uber aberta): a 1ª distância da tela é a da próxima curva.
        val tela = listOf(
            "0.8 km", "Rua Washington Luiz", "para Rua General Salus...", "Praça da Alfândega", "30", "LIMITE",
            "Navegar", "5 min", "1,8 km", "Encontro com Rafaela",
        )
        assertEquals(accepted("rafaela", 1.8), TripScreens.detect(tela))
        assertEquals(onTrip("ana", 11.3), TripScreens.detect(listOf("0,3 km", "Av. Ipiranga", "21 min 11,3 km", "Destino de Ana")))
    }

    @Test fun waze_na_frente_a_corrida_toda_nao_vira_cancelamento() {
        // Nem embarque nem fim lidos; 15 min depois (corrida prevista de 14 min) aparece outro passageiro.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 14 * min)
        m.onStage(accepted("ana"), 2_000); m.markCounted("ana")
        m.offerSeen("bruno", 15 * min)
        val r = m.onStage(accepted("bruno"), 15 * min + 3_000)
        assertEquals(1, r.finishedUnseen)
        assertFalse(r.cancelledPickup)
    }

    @Test fun tela_inicial_fecha_as_corridas_abertas() {
        // Embarcou e o fim passou rápido: "Procurando viagens" finaliza.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 14 * min)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 4 * min)
        val r = m.onStage(idle, 15 * min)
        assertEquals(1, r.finishedUnseen)
        assertEquals(0, m.openRides)
        assertEquals(0, m.onStage(idle, 16 * min).finishedUnseen) // nada mais aberto
    }

    @Test fun tela_inicial_sem_embarque_decide_pelo_tempo() {
        val m = TripMatcher<String>()
        m.offerSeen("caio", 0, expectedMs = 20 * min)
        m.onStage(accepted("caio"), 1_000); m.markCounted("caio")
        // Logo depois do aceite: pode ser a transição da Uber, espera.
        val cedo = m.onStage(idle, 30_000)
        assertEquals(0, cedo.finishedUnseen)
        assertFalse(cedo.cancelledPickup)
        assertEquals(1, m.openRides)
        // 4 min depois, ainda sem embarque: cancelada.
        assertTrue(m.onStage(idle, 4 * min).cancelledPickup)
        // Outra, que ficou aberta tempo da corrida inteira: finalizada.
        m.offerSeen("bia", 10 * min, expectedMs = 12 * min)
        m.onStage(accepted("bia"), 10 * min + 1_000); m.markCounted("bia")
        assertEquals(1, m.onStage(idle, 22 * min).finishedUnseen)
    }

    @Test fun tela_inicial_de_um_app_nao_fecha_corrida_de_outro() {
        // Corrida da 99 em andamento; a Uber continua ligada em "Procurando viagens".
        val m = TripMatcher<String>()
        m.offerSeen("da99", 0, expectedMs = 10 * min, platform = Platform.NINETY_NINE)
        m.manualCount("da99", 5_000)
        val uber = TripEvent(TripStage.IDLE, platform = Platform.UBER)
        assertEquals(0, m.onStage(uber, 20 * min).finishedUnseen)
        assertEquals(1, m.openRides)
        // Tela da Uber não conta oferta da 99.
        assertNull(m.onStage(TripEvent(TripStage.ACCEPTED, "caio", platform = Platform.UBER), 20 * min).count)
        val r = m.onStage(TripEvent(TripStage.IDLE, platform = Platform.NINETY_NINE), 21 * min)
        assertEquals(1, r.finishedUnseen)
        assertEquals(Platform.NINETY_NINE, r.platform)
    }

    @Test fun mais1_manual_tambem_fecha_sozinho() {
        val m = TripMatcher<String>()
        m.offerSeen("o1", 0, expectedMs = 10 * min)
        m.manualCount("o1", 5_000)
        assertEquals(1, m.openRides)
        assertNull(m.onStage(onTrip("caio"), 4 * min).count)        // nome é da corrida do +1
        assertEquals(1, m.onStage(idle, 12 * min).finishedUnseen)
        // ✓/✕ à mão tira do acompanhamento.
        m.offerSeen("o2", 20 * min)
        m.manualCount("o2", 20 * min)
        m.closedNewest()
        assertEquals(0, m.openRides)
    }

    @Test fun oferta_ja_contada_no_mais1_nao_conta_de_novo() {
        val m = TripMatcher<String>()
        m.offerSeen("o1", 0)
        m.markCounted("o1")
        assertNull(m.onStage(accepted("caio"), 5_000).count)
        m.forget("o1")                                              // desfeita
        assertEquals("o1", m.onStage(accepted("bia"), 6_000).count)
    }

    @Test fun sem_nome_so_aceite_recente() {
        val m = TripMatcher<String>()
        m.offerSeen("o1", 0)
        assertEquals("o1", m.onStage(accepted(), 30_000).count)
        assertFalse(m.onStage(TripEvent(TripStage.ON_TRIP), 40_000).newRide)
    }

    @Test fun nomes_parecidos() {
        assertTrue(TripMatcher.sameName("caio", "caioaugusto"))
        assertFalse(TripMatcher.sameName("ana", "bruno"))
        assertFalse(TripMatcher.sameName("a", "ana"))
        assertTrue(TripMatcher.sameName("caio", "calo"))            // uma letra lida errada
        assertFalse(TripMatcher.sameName("ana", "aria"))
    }

    @Test fun embarque_confirma_e_busca_sem_embarque_e_cancelada() {
        val m = TripMatcher<String>()
        m.offerSeen("caio", 0)
        assertEquals("caio", m.onStage(accepted("caio"), 5_000).count)
        m.markCounted("caio")
        // Caio cancelou antes de embarcar; nova oferta aceita → "Encontro com Ana".
        m.offerSeen("ana", 8 * min)
        val r = m.onStage(accepted("ana"), 8 * min + 10_000)
        assertTrue(r.cancelledPickup)
        assertEquals("ana", r.count)
        m.markCounted("ana")
        // Ana embarca: viagem confirmada (uma vez só).
        assertTrue(m.onStage(onTrip("ana"), 15 * min).started)
        assertFalse(m.onStage(onTrip("ana"), 16 * min).started)
    }

    @Test fun corrida_em_fila_nao_cancela_quem_esta_a_bordo() {
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 10 * min)
        val r = m.onStage(accepted("bruno"), 11 * min)             // aparece com a Ana a bordo
        assertFalse(r.cancelledPickup)
        assertEquals("bruno", r.count)
    }

    @Test fun fim_sem_embarque_lido_nao_cancela_a_proxima() {
        // Waze na frente: "Destino de" nunca lido, mas "Como foi a viagem?" sim.
        val m = TripMatcher<String>()
        m.offerSeen("caio", 0)
        m.onStage(accepted("caio"), 1_000); m.markCounted("caio")
        m.onStage(finished, 30 * min)
        m.offerSeen("ana", 31 * min)
        val r = m.onStage(accepted("ana"), 31 * min + 5_000)
        assertFalse(r.cancelledPickup)
        assertEquals("ana", r.count)
    }

    // ------------------------------------------------------------------ memória de ofertas (15/11)

    @Test fun memoria_salva_volta_e_conta_a_corrida_da_fila_depois_de_reabrir() {
        // Android matou o copiloto no meio da viagem da Ana, com o Bruno aceito na fila.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 25 * min)
        m.onStage(accepted("ana"), 2_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 6 * min)
        m.offerSeen("bruno", 20 * min, pickupKm = 1.2)
        val salvo = m.snapshot(21 * min) { it }
        val reaberto = TripMatcher<String>()
        assertTrue(reaberto.restore(salvo, 25 * min) { it })
        assertEquals(1, reaberto.openRides)
        assertEquals(1, reaberto.rememberedOffers)
        val r = reaberto.onStage(accepted("bruno", km = 1.1), 31 * min)
        assertEquals("bruno", r.count)
        assertEquals(1, r.finishedUnseen)                            // Ana (a bordo) terminou
        // Memória velha (outro dia) ou estragada não volta.
        assertFalse(TripMatcher<String>().restore(salvo, 21 * min + TripMatcher.MAX_RESTORE_MS + 1) { it })
        assertFalse(TripMatcher<String>().restore("lixo", 0) { it })
    }

    @Test fun passageiro_que_ja_saiu_aparecendo_de_novo_nao_gasta_a_oferta_da_fila() {
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 20 * min)                               // aceita na fila
        m.onStage(finished, 29 * min)                                // Ana desceu
        val resto = m.onStage(onTrip("ana"), 29 * min + 25_000)      // "Destino de Ana" ainda na tela
        assertNull(resto.count)
        assertFalse(resto.newRide)
        assertEquals("bruno", m.onStage(accepted("bruno"), 30 * min).count)
    }

    @Test fun embarque_logo_depois_da_oferta_e_de_quem_ja_estava_a_bordo() {
        // Copiloto ligado no meio da viagem da Ana: o MApp não viu a viagem começar.
        val m = TripMatcher<String>()
        m.offerSeen("bruno", 0, pickupKm = 2.0, pickupMin = 5)
        // Aceitou e a Uber voltou para "Destino de Ana": não dá tempo de buscar o Bruno.
        assertNull(m.onStage(onTrip("ana"), 20_000).count)
        // A oferta ficou na fila: vale mesmo mais de 20 min depois (aceite não lido teria vencido).
        assertEquals("bruno", m.onStage(accepted("bruno", km = 2.1), 35 * min).count)
    }

    @Test fun corrida_do_mais1_sem_nome_nao_pega_o_passageiro_da_fila() {
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 20 * min)
        m.manualCount("ana", 30_000)                                 // +1: corrida sem nome
        m.offerSeen("bruno", 18 * min)                               // aceita na fila
        val r = m.onStage(accepted("bruno"), 24 * min)
        assertEquals("bruno", r.count)
        assertEquals(1, r.finishedUnseen)                            // a do +1 terminou
        assertEquals(1, m.openRides)
    }

    @Test fun oferta_recusada_antes_da_aceita_sai_da_memoria() {
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("ruim", 12 * min)                                // recusada no X (sem tela inicial)
        m.offerSeen("boa", 14 * min)                                 // aceita na fila
        assertEquals(2, m.rememberedOffers)
        assertEquals("boa", m.onStage(accepted("bruno"), 25 * min).count)
        m.markCounted("boa")
        assertEquals(0, m.rememberedOffers)
        // Próxima corrida com a oferta não lida: a recusada não volta.
        m.onStage(onTrip("bruno"), 30 * min)
        assertNull(m.onStage(accepted("carla"), 50 * min).count)
    }

    @Test fun fila_aguenta_tela_inicial_rapida_na_troca_de_corrida() {
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0)
        m.onStage(accepted("ana"), 1_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        m.offerSeen("bruno", 20 * min)
        m.onStage(finished, 29 * min)
        m.onStage(idle, 29 * min + 10_000)                           // "Procurando viagens" piscou
        assertEquals("bruno", m.onStage(accepted("bruno"), 30 * min).count)
        // Tela inicial de verdade (minutos): a oferta da fila não foi aceita.
        val m2 = TripMatcher<String>()
        m2.offerSeen("ana", 0)
        m2.onStage(accepted("ana"), 1_000); m2.markCounted("ana")
        m2.onStage(onTrip("ana"), 5 * min)
        m2.offerSeen("bruno", 20 * min)
        m2.onStage(idle, 29 * min)
        assertNull(m2.onStage(accepted("carla"), 40 * min).count)
    }

    @Test fun oferta_mais_nova_que_nao_cabe_na_distancia_cede_para_a_anterior() {
        val m = TripMatcher<String>()
        m.offerSeen("certa", 0, pickupKm = 1.0, tripKm = 3.0)
        m.offerSeen("outra", 20_000, pickupKm = 0.5, tripKm = 1.0)   // cartão relido errado / recusada
        assertEquals("certa", m.onStage(onTrip("caio", km = 3.1), 9 * min).count)
    }

    @Test fun nome_mal_lido_no_meio_da_viagem_nao_finaliza_a_corrida() {
        // Diagnóstico de 26/11/2026 (0.3.225): Comfort R$ 30,44, busca 5,0 km/11 min, viagem 15,7 km/30 min
        // (06:11:54). Às 06:28:39 o OCR leu "Destino de sim" com 12,8 km até o destino — o mesmo passageiro,
        // nome mal lido. Antes isso virava passageiro novo e a corrida era finalizada 25 min antes do previsto.
        val m = TripMatcher<String>()
        m.offerSeen("r30", 0, pickupKm = 5.0, tripKm = 15.7, expectedMs = 41 * min, platform = Platform.UBER, pickupMin = 11)
        val uber = { e: TripEvent -> e.copy(platform = Platform.UBER) }
        assertEquals("r30", m.onStage(uber(accepted("gabriel", km = 5.0)), 30_000).count)
        m.markCounted("r30")
        assertTrue(m.onStage(uber(onTrip("gabriel", km = 15.7)), 12 * min).started)
        val r = m.onStage(uber(onTrip("sim", km = 12.8)), 16 * min + 45_000)
        assertTrue(r.misreadName)
        assertEquals(0, r.finishedUnseen)
        assertNull(r.count)
        assertEquals(false, r.newRide)
        assertEquals(1, m.openRides)
        // O nome certo volta: continua a mesma corrida, nada muda.
        val again = m.onStage(uber(onTrip("gabriel", km = 9.0)), 20 * min)
        assertEquals(0, again.finishedUnseen)
        assertEquals(false, again.newRide)
        assertEquals(1, m.openRides)
    }

    @Test fun nome_mal_lido_no_embarque_conta_como_embarque_da_corrida_aberta() {
        // O embarque só foi lido com o nome errado: é o passageiro da busca que embarcou.
        val m = TripMatcher<String>()
        m.offerSeen("o1", 0, pickupKm = 2.0, tripKm = 8.0, expectedMs = 25 * min, pickupMin = 6)
        m.onStage(accepted("gabriel"), 20_000); m.markCounted("o1")
        val r = m.onStage(onTrip("sim", km = 8.0), 7 * min)
        assertTrue(r.misreadName)
        assertTrue(r.started)
        assertEquals(0, r.finishedUnseen)
        assertEquals(false, r.cancelledPickup)
        assertEquals(1, m.openRides)
    }

    @Test fun passageiro_novo_sem_oferta_depois_do_tempo_da_corrida_ainda_fecha_a_anterior() {
        // A corrida aberta já teve tempo de acabar (¾ do previsto): nome novo sem oferta lida é corrida nova
        // (fila com a oferta não lida) e a anterior é finalizada, como antes.
        val m = TripMatcher<String>()
        m.offerSeen("ana", 0, expectedMs = 14 * min)
        m.onStage(accepted("ana"), 10_000); m.markCounted("ana")
        m.onStage(onTrip("ana"), 5 * min)
        val r = m.onStage(accepted("bruno"), 20 * min)
        assertEquals(false, r.misreadName)
        assertTrue(r.newRide)
        assertEquals(1, r.finishedUnseen)
    }
}
