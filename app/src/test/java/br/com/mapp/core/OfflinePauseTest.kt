package br.com.mapp.core

import br.com.mapp.core.model.Platform
import br.com.mapp.core.shift.OfflineMark
import br.com.mapp.core.shift.OfflinePause
import br.com.mapp.core.shift.OfflineStep
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePauseTest {

    private val min = 60_000L
    private val start = 1_000_000L
    /** Jornada rodando há 2 h, com 50 km. */
    private val running = Shift(startedAt = start, gpsKm = 50.0)
    private val offAt = start + 120 * min

    private fun markOf(shift: Shift = running) = OfflinePause.mark(shift, offAt)!!

    @Test fun marca_so_com_jornada_rodando() {
        assertEquals(OfflineMark(offAt, 50.0, start), OfflinePause.mark(running, offAt))
        assertNull(OfflinePause.mark(running.pause(offAt - min), offAt))
        assertNull(OfflinePause.mark(null, offAt))
    }

    @Test fun espera_ate_o_prazo() {
        val mark = markOf()
        assertEquals(OfflineStep.Wait, OfflinePause.step(running, mark, 10, false, offAt + 9 * min))
    }

    @Test fun pausa_desde_que_ficou_offline_com_tempo_e_km_daquele_momento() {
        val mark = markOf()
        // Rodou mais 6 km voltando para casa depois de ficar offline.
        val later = running.copy(gpsKm = 56.0)
        val step = OfflinePause.step(later, mark, 10, false, offAt + 11 * min)
        assertTrue(step is OfflineStep.Pause)
        val paused = (step as OfflineStep.Pause).shift
        assertTrue(paused.paused)
        assertEquals(120 * min, paused.elapsedMs(offAt + 60 * min))
        assertEquals(50.0, paused.gpsKm, 1e-9)
        assertEquals(start, paused.startedAt)
    }

    @Test fun corrida_depois_de_ficar_offline_recomeca_o_prazo() {
        val mark = markOf()
        val trip = ShiftTrip(offAt + 3 * min, Platform.NINETY_NINE, null, 15.0, 1.0, 4.0, finished = true)
        val withTrip = running.copy(gpsKm = 55.0, trips = listOf(trip))
        val now = offAt + 20 * min
        val step = OfflinePause.step(withTrip, mark, 10, false, now)
        assertEquals(OfflineStep.Restart(OfflineMark(now, 55.0, start)), step)
    }

    @Test fun corrida_aberta_recomeca_o_prazo() {
        val mark = markOf()
        val now = offAt + 30 * min
        assertEquals(
            OfflineStep.Restart(OfflineMark(now, 50.0, start)),
            OfflinePause.step(running, mark, 10, rideOpen = true, now = now),
        )
    }

    @Test fun pausada_encerrada_outra_jornada_ou_desligado_apaga_a_marca() {
        val mark = markOf()
        val now = offAt + 20 * min
        assertEquals(OfflineStep.Drop, OfflinePause.step(running.pause(offAt + min), mark, 10, false, now))
        assertEquals(OfflineStep.Drop, OfflinePause.step(null, mark, 10, false, now))
        assertEquals(OfflineStep.Drop, OfflinePause.step(Shift(startedAt = offAt + min), mark, 10, false, now))
        assertEquals(OfflineStep.Drop, OfflinePause.step(running, mark, OfflinePause.OFF, false, now))
    }

    @Test fun retomada_a_mao_depois_de_offline_vale_a_decisao_do_motorista() {
        val mark = markOf()
        val resumed = running.pause(offAt + min).resume(offAt + 2 * min)
        assertEquals(OfflineStep.Drop, OfflinePause.step(resumed, mark, 10, false, offAt + 30 * min))
    }

    @Test fun volta_sozinha_so_a_jornada_que_pausou_por_offline() {
        val paused = running.pause(offAt)
        assertTrue(OfflinePause.resumeOnOnline(paused, start))
        assertFalse(OfflinePause.resumeOnOnline(paused, 0L))
        assertFalse(OfflinePause.resumeOnOnline(paused, start + 1))
        assertFalse(OfflinePause.resumeOnOnline(running, start))
        assertFalse(OfflinePause.resumeOnOnline(null, start))
    }

    @Test fun opcoes_e_valor_salvo_invalido() {
        assertEquals(OfflinePause.DEFAULT_MINUTES, OfflinePause.sanitize(7))
        assertEquals(30, OfflinePause.sanitize(30))
        assertEquals(OfflinePause.OFF, OfflinePause.sanitize(OfflinePause.OFF))
        assertEquals("Desligado", OfflinePause.label(0))
        assertEquals("15 min", OfflinePause.label(15))
    }
}
