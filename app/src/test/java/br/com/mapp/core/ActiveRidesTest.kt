package br.com.mapp.core

import br.com.mapp.core.shift.ActiveRides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRidesTest {

    private val min = 60_000L

    @Test fun uma_corrida_e_a_atual_sem_proxima() {
        val r = ActiveRides<String, String>()
        r.accepted("a", "R$ 15", at = 0L, passenger = "caio")
        val (cur, next) = r.current(open = 1, now = 5 * min)
        assertEquals("a", cur?.key)
        assertEquals("caio", cur?.passenger)
        assertNull(next)
    }

    @Test fun corrida_aceita_durante_outra_e_a_proxima() {
        val r = ActiveRides<String, String>()
        r.accepted("a", "R$ 15", at = 0L)
        r.accepted("b", "R$ 22", at = 10 * min)
        val (cur, next) = r.current(open = 2, now = 12 * min)
        assertEquals("a", cur?.key)
        assertEquals("b", next?.key)
    }

    @Test fun a_atual_terminou_a_proxima_vira_atual() {
        val r = ActiveRides<String, String>()
        r.accepted("a", "R$ 15", at = 0L)
        r.accepted("b", "R$ 22", at = 10 * min)
        // O acompanhamento fechou a mais antiga: sobra 1 aberta.
        val (cur, next) = r.current(open = 1, now = 20 * min)
        assertEquals("b", cur?.key)
        assertNull(next)
        // Corte é definitivo: a que terminou não volta.
        assertEquals("b", r.current(open = 2, now = 21 * min).first?.key)
        assertEquals(1, r.size)
    }

    @Test fun sem_corrida_aberta_fica_vazio() {
        val r = ActiveRides<String, String>()
        r.accepted("a", "R$ 15", at = 0L)
        val (cur, next) = r.current(open = 0, now = min)
        assertNull(cur)
        assertNull(next)
    }

    @Test fun cancelada_sai_na_hora() {
        val r = ActiveRides<String, String>()
        r.accepted("a", "R$ 15", at = 0L)
        r.accepted("b", "R$ 22", at = 10 * min)
        assertTrue(r.removed("a"))
        assertFalse(r.removed("x"))
        assertEquals("b", r.current(open = 2, now = 11 * min).first?.key)
    }

    @Test fun mesma_oferta_de_novo_so_atualiza() {
        val r = ActiveRides<String, String>()
        r.accepted("a", "R$ 15", at = 0L, passenger = "caio")
        r.accepted("a", "R$ 15 completa", at = 5 * min, passenger = null)
        val cur = r.current(open = 1, now = 6 * min).first
        assertEquals(1, r.size)
        assertEquals(0L, cur?.acceptedAt) // hora do 1º aceite
        assertEquals("caio", cur?.passenger) // nome não se perde
        assertEquals("R$ 15 completa", cur?.data)
        // Oferta completada depois (endereço localizado): só a corrida aberta muda.
        assertTrue(r.updated("a", "com endereço"))
        assertFalse(r.updated("a", "com endereço"))
        assertFalse(r.updated("x", "outra"))
        assertEquals("com endereço", r.data("a"))
    }

    @Test fun ordem_pela_hora_do_aceite_e_limites() {
        val r = ActiveRides<String, String>(maxAgeMs = 60 * min, max = 3)
        // Restaurada do histórico depois (hora mais antiga) entra no lugar certo.
        r.accepted("b", "R$ 22", at = 10 * min)
        r.accepted("a", "R$ 15", at = 0L)
        assertEquals("a", r.current(open = 2, now = 11 * min).first?.key)
        // Velha demais sai mesmo com o acompanhamento dizendo que está aberta.
        val (cur, next) = r.current(open = 2, now = 65 * min)
        assertEquals("b", cur?.key)
        assertNull(next)
        // No máximo [max] guardadas.
        listOf("c", "d", "e", "f").forEachIndexed { i, k -> r.accepted(k, k, at = (20 + i) * min) }
        assertEquals(3, r.size)
    }
}
