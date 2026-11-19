package br.com.mapp.core

import br.com.mapp.core.goal.GoalForecast
import br.com.mapp.core.goal.GoalPace
import br.com.mapp.core.goal.GoalPlan
import br.com.mapp.core.repo.Goals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class GoalPaceTest {
    private val eps = 1e-6
    private val nov = YearMonth.of(2026, 11) // 01/11/2026 é domingo; 30/11 é segunda
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private val week = GoalPlan.weekDays(d(16)) // seg 16 a dom 22
    private val equal: (LocalDate) -> Double = { 1.0 }

    /** Geral R$ 250, sábado e domingo R$ 1.000. */
    private val weekend = Goals(daily = 250.0, byWeekday = mapOf(6 to 1000.0, 7 to 1000.0))

    // --- Meta mensal dividida pelos dias e semanas ---

    @Test fun meta_mensal_sem_metas_por_dia_divide_igual() {
        val g = Goals(monthly = 6000.0)
        assertEquals(200.0, GoalPlan.monthShare(g, d(5))!!, eps)
        val slices = GoalPlan.monthWeeks(g, nov)
        assertEquals(listOf(200.0, 1400.0, 1400.0, 1400.0, 1400.0, 200.0), slices.map { it.amount })
        assertEquals(6000.0, slices.sumOf { it.amount }, eps)
        assertEquals(d(1), slices.first().to) // só o domingo 01/11
        assertFalse(slices.first().whole)
        assertTrue(slices[1].whole)
        assertEquals(d(30) to d(30), slices.last().from to slices.last().to)
        // Semana inteira em novembro.
        val w = GoalPlan.weekGoal(g, d(16))!!
        assertEquals(1400.0, w.amount, eps)
        assertEquals(GoalPlan.WeekSource.MONTHLY, w.source)
        // Semana que vira o mês: 30/11 com a parte de novembro + 01 a 06/12 com a de dezembro (31 dias).
        assertEquals(200.0 + 6 * 6000.0 / 31, GoalPlan.weekGoal(g, d(30))!!.amount, eps)
    }

    @Test fun meta_mensal_pesa_as_metas_de_cada_dia() {
        val g = weekend.copy(monthly = 7125.0)
        // Novembro/2026: 9 dias de fim de semana (×1.000) e 21 dias úteis (×250) = 14.250 → metade.
        assertEquals(500.0, GoalPlan.monthShare(g, d(21))!!, eps) // sábado
        assertEquals(125.0, GoalPlan.monthShare(g, d(18))!!, eps) // quarta
        assertEquals(5 * 125.0 + 2 * 500.0, GoalPlan.weekGoal(g, d(16))!!.amount, eps)
        assertEquals(7125.0, GoalPlan.monthWeeks(g, nov).sumOf { it.amount }, eps)
        // Só metas por dia (sem mensal): a meta da semana é a soma delas.
        val daily = GoalPlan.weekGoal(weekend, d(16))!!
        assertEquals(5 * 250.0 + 2 * 1000.0, daily.amount, eps)
        assertEquals(GoalPlan.WeekSource.DAILY, daily.source)
        assertNull(GoalPlan.weekGoal(Goals(), d(16)))
        assertTrue(GoalPlan.monthWeeks(weekend, nov).isEmpty())
    }

    // --- Em que dia bate a meta no ritmo atual ---

    @Test fun hoje_em_andamento_fecha_como_um_dia_normal() {
        // Seg 300, ter 250, quarta (hoje) 80 até agora: ritmo 275/dia.
        val f = GoalPace.forecast(1800.0, 630.0, 80.0, week, d(18), equal)
        assertEquals(GoalForecast.ReachOn(d(22), 275.0), f)
        val short = GoalPace.forecast(2000.0, 630.0, 80.0, week, d(18), equal) as GoalForecast.Short
        assertEquals(1925.0, short.projected, eps) // 550 + 275 × 5
        assertEquals(75.0, short.missing, eps)
        assertEquals(5, short.daysLeft) // hoje + qui a dom
        assertEquals((2000.0 - 630.0) / 5, short.neededPerDay!!, eps)
    }

    @Test fun hoje_ja_rendeu_mais_que_o_normal_entra_no_ritmo() {
        // Seg 300, ter 250, quarta (hoje) já 400: ritmo 950/3.
        val f = GoalPace.forecast(1800.0, 950.0, 400.0, week, d(18), equal)
        assertEquals(d(21), (f as GoalForecast.ReachOn).date)
        assertEquals(950.0 / 3, f.perDay, eps)
    }

    @Test fun pode_bater_hoje_mesmo() {
        // Seg a ter 800 por dia; hoje 100 até agora: fecha hoje com 1.600 + 800.
        assertEquals(GoalForecast.ReachOn(d(18), 800.0), GoalPace.forecast(2200.0, 1700.0, 100.0, week, d(18), equal))
    }

    @Test fun primeiro_dia_com_ganho_conta_os_dias_parados() {
        // Seg e ter parado, quarta 300: média de 100/dia.
        val f = GoalPace.forecast(1800.0, 300.0, 300.0, week, d(18), equal) as GoalForecast.Short
        assertEquals(700.0, f.projected, eps)
        assertEquals(100.0, f.perDay, eps)
        assertEquals(300.0, f.neededPerDay!!, eps)
        // Dia 1 do mês: o ritmo é o de hoje (200 × 30 = 6.000).
        val month = GoalPlan.daysOf(nov)
        assertEquals(GoalForecast.ReachOn(d(30), 200.0), GoalPace.forecast(6000.0, 200.0, 200.0, month, d(1), equal))
    }

    @Test fun ritmo_respeita_o_peso_das_metas_por_dia() {
        // Seg e ter 250 cada (o normal), hoje nada: no ritmo, o sábado de R$ 1.000 fecha a meta.
        val w = { day: LocalDate -> GoalPlan.weight(weekend, day) }
        assertEquals(GoalForecast.ReachOn(d(21), 250.0), GoalPace.forecast(2000.0, 500.0, 0.0, week, d(18), w))
        // Sem pesos (todos zero) divide igual em vez de travar.
        assertEquals(GoalForecast.ReachOn(d(22), 275.0), GoalPace.forecast(1800.0, 630.0, 80.0, week, d(18)) { 0.0 })
    }

    @Test fun batida_sem_ritmo_e_periodo_ja_acabado() {
        assertEquals(GoalForecast.Reached, GoalPace.forecast(1000.0, 1000.0, 0.0, week, d(18), equal))
        assertEquals(GoalForecast.NoPace, GoalPace.forecast(1000.0, 0.0, 0.0, week, d(18), equal))
        // Segunda 2h da semana seguinte (fora do período): não sobra dia.
        val f = GoalPace.forecast(1800.0, 1000.0, 50.0, week, d(23), equal) as GoalForecast.Short
        assertEquals(1000.0, f.projected, eps)
        assertEquals(0, f.daysLeft)
        assertNull(f.neededPerDay)
    }
}
