package br.com.mapp.core

import br.com.mapp.core.status.ReaderCheck
import br.com.mapp.core.status.ReaderProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCheckTest {

    @Test fun conectado_nao_tem_problema() {
        assertEquals(ReaderProblem.NONE, ReaderCheck.problem(settingOn = true, connected = true))
        assertNull(ReaderCheck.notice(ReaderProblem.NONE))
    }

    @Test fun desligado_nas_configuracoes() {
        assertEquals(ReaderProblem.ACCESSIBILITY_OFF, ReaderCheck.problem(settingOn = false, connected = false))
    }

    @Test fun ligado_mas_sem_conexao_e_o_caso_do_print() {
        // Tela inicial dizia "Tudo ativado" (Acessibilidade marcada) e o ícone ficava cinza com "!".
        val p = ReaderCheck.problem(settingOn = true, connected = false)
        assertEquals(ReaderProblem.NOT_CONNECTED, p)
        val msg = ReaderCheck.message(p)
        assertTrue(msg.contains("não conectou"))
        assertTrue(msg.contains("Desligue e ligue"))
    }

    @Test fun conectado_vale_mesmo_sem_a_marca_na_lista() {
        // Alguns aparelhos gravam a lista de outro jeito: se o sistema conectou, está tudo certo.
        assertEquals(ReaderProblem.NONE, ReaderCheck.problem(settingOn = false, connected = true))
    }

    @Test fun cada_problema_tem_aviso_e_titulo_proprios() {
        val off = ReaderProblem.ACCESSIBILITY_OFF
        val lost = ReaderProblem.NOT_CONNECTED
        assertNotNull(ReaderCheck.notice(off))
        assertNotNull(ReaderCheck.notice(lost))
        assertTrue(ReaderCheck.message(off) != ReaderCheck.message(lost))
        assertTrue(ReaderCheck.title(off) != ReaderCheck.title(lost))
    }
}
