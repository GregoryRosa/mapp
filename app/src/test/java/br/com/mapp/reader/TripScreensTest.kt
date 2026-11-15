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
        // E fim sem nada devido continua fechando na hora, mesmo corrida curta.
        val e = TripMatcher<String>()
        e.offerSeen("o1", 0, expectedMs = 30 * min)
        e.onStage(accepted("caio"), 5_000); e.markCounted("o1")
        assertFalse(e.onStage(finished, 3 * min).lateFinish)
        assertEquals(0, e.openRides)
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
}
