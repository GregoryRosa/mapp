package br.com.mapp.core

import br.com.mapp.core.model.Platform
import br.com.mapp.core.shift.ManualRide
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encerrar corrida à mão (seção do painel ⚙ do ícone "M"). */
class ManualRideTest {
    private val h = 3_600_000L

    private fun trip(at: Long, price: Double, cat: String? = null, finished: Boolean = false) =
        ShiftTrip(at, Platform.UBER, cat, price, 1.0, 5.0, finished = finished)

    @Test fun nada_aberto_nao_tem_o_que_encerrar() {
        val s = ManualRide.state(Shift(startedAt = 0, trips = listOf(trip(1 * h, 10.0, finished = true))), trackedOpen = 0)
        assertFalse(s.any)
        assertEquals(0, s.open)
        assertNull(s.oldest)
        assertNull(s.newest)
        assertNull(ManualRide.label(s.oldest))
    }

    @Test fun mais_um_sem_querer_sai_pelo_x() {
        // +1 tocado no Card: a corrida fica na jornada em andamento e as telas nunca confirmam o fim.
        val fake = trip(2 * h, 23.4, "UberX")
        val s = ManualRide.state(Shift(startedAt = 0, trips = listOf(fake)), trackedOpen = 1)
        assertTrue(s.any)
        assertEquals(1, s.open)
        assertEquals(0, s.queued)
        assertFalse(s.outsideShift)
        // ✕ tira a mais recente, ✓ finaliza a mais antiga: com uma só, é a mesma.
        assertEquals(fake, s.newest)
        assertEquals(fake, s.oldest)
        assertEquals("UberX · R$ 23,40", ManualRide.label(s.oldest))
    }

    @Test fun corrida_na_fila_o_check_fecha_a_mais_antiga() {
        val first = trip(1 * h, 10.0, "UberX")
        val queued = trip(2 * h, 18.0, "Comfort")
        val s = ManualRide.state(Shift(startedAt = 0, trips = listOf(first, queued)), trackedOpen = 2)
        assertEquals(2, s.open)
        assertEquals(1, s.queued)
        assertEquals(first, s.oldest)
        assertEquals(queued, s.newest)
    }

    @Test fun aceita_sem_jornada_fica_fora_da_jornada() {
        // "M" verde sem jornada aberta: a corrida só existe no acompanhamento do aceite.
        val s = ManualRide.state(null, trackedOpen = 1)
        assertTrue(s.any)
        assertTrue(s.outsideShift)
        assertFalse(s.hasShift)
        assertNull(s.oldest)
    }

    @Test fun jornada_manda_quando_o_acompanhamento_nao_viu_a_corrida() {
        // Corrida contada pelo +1 com o leitor sem ler nenhuma tela: a jornada tem 1, o acompanhamento 0.
        val s = ManualRide.state(Shift(startedAt = 0, trips = listOf(trip(1 * h, 10.0))), trackedOpen = 0)
        assertEquals(1, s.open)
        assertFalse(s.outsideShift)
        assertEquals("Uber · R$ 10,00", ManualRide.label(s.oldest))
    }

    @Test fun tipo_antigo_eletrico_aparece_como_electric() {
        val s = ManualRide.state(Shift(startedAt = 0, trips = listOf(trip(1 * h, 31.5, "Elétrico"))), trackedOpen = 1)
        assertEquals("Electric · R$ 31,50", ManualRide.label(s.oldest))
    }
}
