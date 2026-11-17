package br.com.mapp.core

import br.com.mapp.core.support.DeviceReport
import br.com.mapp.core.support.SupportRules
import br.com.mapp.core.support.Ticket
import br.com.mapp.core.support.TicketKind
import br.com.mapp.core.support.TicketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportRulesTest {

    private fun ticket(
        id: String,
        status: TicketStatus = TicketStatus.OPEN,
        waiting: Boolean = true,
        updatedAt: Long = 0L,
        unread: Boolean = false,
    ) = Ticket(id = id, status = status, waitingSupport = waiting, updatedAt = updatedAt, unreadForUser = unread)

    @Test fun texto_curto_demais_nao_vira_mensagem() {
        assertNull(SupportRules.clean(" "))
        assertNull(SupportRules.clean("a"))
        assertEquals("oi", SupportRules.clean("  oi  "))
    }

    @Test fun mensagem_gigante_e_cortada() {
        val longa = "x".repeat(SupportRules.MAX_MESSAGE + 500)
        assertEquals(SupportRules.MAX_MESSAGE, SupportRules.clean(longa)?.length)
    }

    @Test fun assunto_vem_da_primeira_linha_com_texto() {
        assertEquals(
            "O card não aparece na Uber",
            SupportRules.subjectOf("\n\nO card não aparece na Uber\nAcontece desde ontem"),
        )
    }

    @Test fun assunto_longo_ganha_reticencias() {
        val s = SupportRules.subjectOf("a".repeat(200))
        assertEquals(SupportRules.MAX_SUBJECT, s.length)
        assertTrue(s.endsWith("…"))
    }

    @Test fun assunto_vazio_tem_padrao() {
        assertEquals("Chamado sem assunto", SupportRules.subjectOf("   \n  "))
    }

    @Test fun painel_atende_primeiro_quem_esperou_mais() {
        val novo = ticket("novo", waiting = true, updatedAt = 500)
        val antigo = ticket("antigo", waiting = true, updatedAt = 100)
        val respondido = ticket("respondido", status = TicketStatus.ANSWERED, waiting = false, updatedAt = 900)
        val resolvido = ticket("resolvido", status = TicketStatus.RESOLVED, waiting = false, updatedAt = 999)
        val ordem = SupportRules.forAdmin(listOf(resolvido, respondido, novo, antigo)).map { it.id }
        assertEquals(listOf("antigo", "novo", "respondido", "resolvido"), ordem)
    }

    @Test fun lista_do_motorista_e_pela_ultima_mexida() {
        val ordem = SupportRules.forUser(
            listOf(ticket("a", updatedAt = 10), ticket("b", updatedAt = 30), ticket("c", updatedAt = 20)),
        ).map { it.id }
        assertEquals(listOf("b", "c", "a"), ordem)
    }

    @Test fun contagens_das_bolinhas() {
        val list = listOf(
            ticket("1", unread = true, waiting = false, status = TicketStatus.ANSWERED),
            ticket("2", unread = false, waiting = true),
            // Resolvido não entra na fila do suporte, mesmo marcado como esperando.
            ticket("3", unread = false, waiting = true, status = TicketStatus.RESOLVED),
        )
        assertEquals(1, SupportRules.unreadForUser(list))
        assertEquals(1, SupportRules.waitingForAdmin(list))
    }

    @Test fun resumo_marca_quem_falou_por_ultimo() {
        val doSuporte = Ticket(lastMessage = "Já corrigi na versão nova", lastFromSupport = true)
        assertTrue(SupportRules.summary(doSuporte).startsWith("Suporte: "))
        val doMotorista = Ticket(lastMessage = "Travou de novo", lastFromSupport = false)
        assertEquals("Travou de novo", SupportRules.summary(doMotorista))
    }

    @Test fun resumo_de_varias_linhas_fica_numa_linha() {
        val t = Ticket(lastMessage = "linha 1\nlinha 2")
        assertTrue(!SupportRules.summary(t).contains("\n"))
    }

    @Test fun rotulos_em_portugues() {
        assertEquals("Aberto", SupportRules.label(TicketStatus.OPEN))
        assertEquals("Respondido", SupportRules.label(TicketStatus.ANSWERED))
        assertEquals("Resolvido", SupportRules.label(TicketStatus.RESOLVED))
        assertTrue(SupportRules.label(TicketKind.BUG).contains("Bug"))
        assertTrue(SupportRules.label(TicketKind.HELP).contains("Suporte"))
    }

    @Test fun diagnostico_tem_o_que_o_suporte_precisa_e_nada_de_corrida() {
        val t = DeviceReport.text(
            appVersion = "0.3.5", androidVersion = "14", model = "Xiaomi Redmi Note 12",
            readerConnected = false, copilotRunning = true, subscription = "ativa",
        )
        assertTrue(t.contains("0.3.5"))
        assertTrue(t.contains("Android 14"))
        assertTrue(t.contains("Redmi"))
        assertTrue(t.contains("sem conexão"))
        assertTrue(t.contains("ligado"))
        assertTrue(t.contains("ativa"))
    }
}
