package br.com.mapp.core

import br.com.mapp.core.goal.DayGoal
import br.com.mapp.core.goal.WeekGoals
import br.com.mapp.core.repo.Goals
import br.com.mapp.core.sound.CashSound
import br.com.mapp.core.sound.SoundSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
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

    // ---------------- Metas por dia da semana ----------------

    /** Meta do usuário: seg/ter/qua 250, qui 300, sex 400, sáb/dom 1000. */
    private fun semana(): Goals {
        var g = Goals(daily = 200.0)
        g = WeekGoals.withDays(g, listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), 250.0)
        g = WeekGoals.withDays(g, listOf(DayOfWeek.THURSDAY), 300.0)
        g = WeekGoals.withDays(g, listOf(DayOfWeek.FRIDAY), 400.0)
        g = WeekGoals.withDays(g, listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 1000.0)
        return g
    }

    @Test fun cada_dia_tem_a_sua_meta() {
        val g = semana()
        assertEquals(250.0, WeekGoals.goalFor(g, DayOfWeek.MONDAY)!!, 1e-9)
        assertEquals(250.0, WeekGoals.goalFor(g, DayOfWeek.WEDNESDAY)!!, 1e-9)
        assertEquals(300.0, WeekGoals.goalFor(g, DayOfWeek.THURSDAY)!!, 1e-9)
        assertEquals(400.0, WeekGoals.goalFor(g, DayOfWeek.FRIDAY)!!, 1e-9)
        assertEquals(1000.0, WeekGoals.goalFor(g, DayOfWeek.SUNDAY)!!, 1e-9)
        // Pela data: 18/11/2026 é uma quarta-feira.
        assertEquals(250.0, WeekGoals.goalFor(g, LocalDate.of(2026, 11, 18))!!, 1e-9)
        assertEquals(1000.0, WeekGoals.goalFor(g, LocalDate.of(2026, 11, 21))!!, 1e-9)
        // O anel do "M" enche com a meta do dia.
        assertEquals(0.5, DayGoal.fraction(125.0, WeekGoals.goalFor(g, DayOfWeek.MONDAY))!!, 1e-9)
        assertFalse(DayGoal.reached(500.0, WeekGoals.goalFor(g, DayOfWeek.SATURDAY)))
    }

    @Test fun dia_sem_meta_propria_usa_a_geral() {
        val g = WeekGoals.withDays(Goals(daily = 250.0), listOf(DayOfWeek.SUNDAY), 1000.0)
        assertEquals(250.0, WeekGoals.goalFor(g, DayOfWeek.MONDAY)!!, 1e-9)
        assertNull(WeekGoals.own(g, DayOfWeek.MONDAY))
        assertEquals(1000.0, WeekGoals.own(g, DayOfWeek.SUNDAY)!!, 1e-9)
        // Sem meta nenhuma: nada de anel.
        assertNull(WeekGoals.goalFor(Goals(), DayOfWeek.MONDAY))
        assertFalse(WeekGoals.hasOwn(Goals(daily = 250.0)))
        assertTrue(WeekGoals.hasOwn(g))
    }

    @Test fun usar_a_geral_tira_a_meta_do_dia() {
        val g = WeekGoals.withDays(semana(), listOf(DayOfWeek.FRIDAY), null)
        assertNull(WeekGoals.own(g, DayOfWeek.FRIDAY))
        assertEquals(200.0, WeekGoals.goalFor(g, DayOfWeek.FRIDAY)!!, 1e-9)
        // Valor inválido também volta para a geral; sem dias escolhidos nada muda.
        assertNull(WeekGoals.own(WeekGoals.withDays(semana(), listOf(DayOfWeek.THURSDAY), 0.0), DayOfWeek.THURSDAY))
        assertEquals(semana(), WeekGoals.withDays(semana(), emptyList(), 999.0))
    }

    @Test fun dias_seguidos_com_a_mesma_meta_viram_um_grupo() {
        val grupos = WeekGoals.groups(semana())
        assertEquals(listOf("Seg a Qua", "Qui", "Sex", "Sáb e Dom"), grupos.map { it.label })
        assertEquals(listOf(250.0, 300.0, 400.0, 1000.0), grupos.map { it.goal })
        // Só a meta geral: a semana inteira num grupo.
        val uma = WeekGoals.groups(Goals(daily = 250.0))
        assertEquals(1, uma.size)
        assertEquals("Seg a Dom", uma.first().label)
        // Sem meta: grupo com meta nula (a tela mostra "sem meta").
        assertNull(WeekGoals.groups(Goals()).first().goal)
    }

    @Test fun metas_da_semana_sobrevivem_ao_salvar() {
        val texto = WeekGoals.encode(semana().byWeekday)
        assertEquals("1=250.00;2=250.00;3=250.00;4=300.00;5=400.00;6=1000.00;7=1000.00", texto)
        assertEquals(semana().byWeekday, WeekGoals.decode(texto))
        assertEquals(emptyMap<Int, Double>(), WeekGoals.decode(null))
        assertEquals(emptyMap<Int, Double>(), WeekGoals.decode(""))
        // Lixo e dia fora de 1..7 são ignorados; o resto continua valendo.
        assertEquals(mapOf(2 to 300.0), WeekGoals.decode("x;9=100;2=300;5=0;=7"))
    }
}
