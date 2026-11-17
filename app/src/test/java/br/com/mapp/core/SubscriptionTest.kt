package br.com.mapp.core

import br.com.mapp.core.billing.Access
import br.com.mapp.core.billing.AccessRules
import br.com.mapp.core.billing.Entitlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionTest {

    private val now = 1_763_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun assinatura_ativa_libera() {
        val e = Entitlement(active = true, checkedAt = now - 1000)
        assertEquals(Access.ALLOWED, AccessRules.access(e, now))
        assertTrue(AccessRules.isOpen(AccessRules.access(e, now)))
    }

    @Test fun sem_assinatura_bloqueia() {
        val e = Entitlement(active = false, checkedAt = now - 1000)
        assertEquals(Access.BLOCKED, AccessRules.access(e, now))
        assertFalse(AccessRules.isOpen(Access.BLOCKED))
    }

    @Test fun primeira_abertura_sem_resposta_do_play_nao_e_paywall() {
        // checkedAt = 0: nunca deu certo conferir. Mostrar "conferindo", não "você não pagou".
        assertEquals(Access.CHECKING, AccessRules.access(Entitlement.NONE, now))
    }

    @Test fun sem_internet_continua_liberado_dentro_da_tolerancia() {
        // Pagou e o último "sim" do Play tem 6 dias: celular sem internet não trava o motorista.
        val e = Entitlement(active = true, checkedAt = now - 6 * day)
        assertEquals(Access.ALLOWED, AccessRules.access(e, now))
    }

    @Test fun passada_a_tolerancia_pede_internet_em_vez_de_dizer_que_nao_pagou() {
        val e = Entitlement(active = true, checkedAt = now - 8 * day)
        assertEquals(Access.STALE, AccessRules.access(e, now))
        assertFalse(AccessRules.isOpen(Access.STALE))
    }

    @Test fun dono_entra_sempre() {
        // O dono não assina de si mesmo: o painel de suporte precisa abrir sem assinatura.
        assertEquals(Access.OWNER, AccessRules.access(Entitlement.NONE, now, owner = true))
        assertTrue(AccessRules.isOpen(Access.OWNER))
    }

    @Test fun apk_fora_da_play_store_nao_e_bloqueado() {
        // APK de teste das Releases do GitHub: não há assinatura para conferir, e bloquear ali só
        // trancaria o desenvolvedor fora do próprio app de trabalho.
        assertEquals(Access.SIDELOAD, AccessRules.access(Entitlement.NONE, now, fromPlay = false))
        assertTrue(AccessRules.isOpen(Access.SIDELOAD))
        // Vale mesmo com o Play tendo dito "não" antes (ex.: assinatura cancelada na loja).
        val negado = Entitlement(active = false, checkedAt = now)
        assertEquals(Access.SIDELOAD, AccessRules.access(negado, now, fromPlay = false))
    }

    @Test fun instalado_pela_loja_continua_bloqueando() {
        // Garante que o caso de cima não virou um buraco: o padrão é conferir.
        val negado = Entitlement(active = false, checkedAt = now)
        assertEquals(Access.BLOCKED, AccessRules.access(negado, now))
        assertEquals(Access.BLOCKED, AccessRules.access(negado, now, fromPlay = true))
    }

    @Test fun dias_restantes_da_tolerancia() {
        assertEquals(7, AccessRules.graceLeft(Entitlement(active = true, checkedAt = now), now))
        assertEquals(1, AccessRules.graceLeft(Entitlement(active = true, checkedAt = now - 6 * day), now))
        assertEquals(0, AccessRules.graceLeft(Entitlement(active = true, checkedAt = now - 9 * day), now))
        assertEquals(0, AccessRules.graceLeft(Entitlement(active = false, checkedAt = now), now))
    }

    @Test fun aviso_so_aparece_quando_falta_pouco() {
        assertNull(AccessRules.graceNotice(Entitlement(active = true, checkedAt = now), now))
        assertNull(AccessRules.graceNotice(Entitlement(active = true, checkedAt = now - 4 * day), now))
        val perto = AccessRules.graceNotice(Entitlement(active = true, checkedAt = now - 6 * day), now)
        assertTrue(perto != null && perto.contains("internet"))
    }

    @Test fun mensagem_do_teste_gratis_e_diferente_da_assinatura_paga() {
        val trial = Entitlement(active = true, checkedAt = now, trial = true)
        val paga = Entitlement(active = true, checkedAt = now)
        assertTrue(AccessRules.message(Access.ALLOWED, trial, now).contains("Teste"))
        assertFalse(AccessRules.message(Access.ALLOWED, paga, now).contains("Teste"))
    }
}
