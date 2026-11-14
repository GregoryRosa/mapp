package br.com.mapp.core

import br.com.mapp.core.goal.DayGoal
import br.com.mapp.core.sound.CashSound
import br.com.mapp.core.sound.SoundSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DayGoalTest {

    @Test fun sem_meta_nao_tem_anel() {
        assertNull(DayGoal.fraction(120.0, null))
        assertNull(DayGoal.fraction(120.0, 0.0))
        assertFalse(DayGoal.reached(120.0, null))
        assertNull(DayGoal.celebration(500.0, null, "2026-11-13", null))
    }

    @Test fun anel_enche_com_o_bruto_ate_100() {
        assertEquals(0.0, DayGoal.fraction(0.0, 300.0)!!, 1e-9)
        assertEquals(0.5, DayGoal.fraction(150.0, 300.0)!!, 1e-9)
        assertEquals(1.0, DayGoal.fraction(450.0, 300.0)!!, 1e-9)
        assertFalse(DayGoal.reached(299.0, 300.0))
        // Soma de corridas com erro de ponto flutuante conta como batida.
        assertTrue(DayGoal.reached(0.1 + 0.2 + 299.7 - 1e-9, 300.0))
        assertEquals(1.0, DayGoal.fraction(299.999, 300.0)!!, 1e-9)
    }

    @Test fun som_toca_uma_vez_por_dia_e_por_meta() {
        val day = "2026-11-13"
        assertNull(DayGoal.celebration(250.0, 300.0, day, null))
        val mark = DayGoal.celebration(310.0, 300.0, day, null)
        assertNotNull(mark)
        // Desfez uma corrida e bateu de novo: não repete.
        assertNull(DayGoal.celebration(320.0, 300.0, day, mark))
        // Meta nova no mesmo dia: comemora a nova.
        assertNotNull(DayGoal.celebration(420.0, 400.0, day, mark))
        // Outro dia: comemora de novo.
        assertNotNull(DayGoal.celebration(310.0, 300.0, "2026-11-14", mark))
    }

    @Test fun som_de_dinheiro_comeca_e_termina_em_silencio() {
        val pcm = CashSound.render()
        assertEquals(CashSound.DURATION_MS * SoundSynth.SAMPLE_RATE / 1000, pcm.size)
        assertEquals(0, pcm.first().toInt())
        assertTrue(abs(pcm.last().toInt()) < 300)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak > Short.MAX_VALUE * 0.5)
        assertTrue(peak <= Short.MAX_VALUE * SoundSynth.PEAK + 1)
        // Sempre o mesmo som (ruído com semente fixa).
        assertTrue(pcm.contentEquals(CashSound.render()))
    }
}
