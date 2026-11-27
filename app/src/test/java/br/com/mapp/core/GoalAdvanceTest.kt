package br.com.mapp.core

import br.com.mapp.core.goal.AdvanceBook
import br.com.mapp.core.goal.AdvanceDay
import br.com.mapp.core.goal.DayRange
import br.com.mapp.core.goal.Diversion
import br.com.mapp.core.goal.GoalAdvance
import br.com.mapp.core.goal.GoalDayState
import br.com.mapp.core.model.Platform
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalAdvanceTest {

    private val day = 20_000L // um dia epoch qualquer
    private val goal = 200.0

    private fun d(amount: Double, onDay: Long = day, at: Long = onDay * 10) =
        Diversion(rideAt = at, platform = "UBER", day = onDay, amount = amount)

    @Test fun sem_meta_nunca_adianta() {
        val r = GoalAdvance.decide(900.0, 30.0, null, GoalDayState(day))
        assertFalse(r.divert)
        assertFalse(GoalAdvance.decide(900.0, 30.0, 0.0, GoalDayState(day)).divert)
    }

    @Test fun corrida_que_bate_a_meta_soma_e_a_seguinte_adianta() {
        var s = GoalDayState(day)
        // 180 + 40 = 220: a corrida que bate a meta soma no dia.
        val bate = GoalAdvance.decide(180.0, 40.0, goal, s)
        assertFalse(bate.divert)
        assertTrue(bate.state.reached)
        s = bate.state
        // Próxima corrida com o lucro na meta: vai para as despesas.
        val proxima = GoalAdvance.decide(220.0, 30.0, goal, s)
        assertTrue(proxima.divert)
        assertFalse(proxima.state.refill)
    }

    @Test fun meta_batida_por_lancamento_tambem_adianta() {
        // Lucro já na meta antes de qualquer corrida (ganho lançado à mão): a corrida adianta.
        assertTrue(GoalAdvance.decide(205.0, 30.0, goal, GoalDayState(day)).divert)
    }

    @Test fun caiu_abaixo_da_meta_repoe_ate_10_por_cento() {
        var s = GoalDayState(day, reached = true)
        // Desgaste dos km derrubou o lucro para 195: a corrida volta a somar e passa a repor até 220.
        var r = GoalAdvance.decide(195.0, 10.0, goal, s)
        assertFalse(r.divert)
        assertTrue(r.state.refill)
        s = r.state
        // 205: acima da meta, mas abaixo de meta + 10% → ainda soma no dia.
        r = GoalAdvance.decide(205.0, 20.0, goal, s)
        assertFalse(r.divert)
        assertTrue(r.state.refill)
        s = r.state
        // 225 ≥ 220: volta a adiantar e sai do modo de repor.
        r = GoalAdvance.decide(225.0, 20.0, goal, s)
        assertTrue(r.divert)
        assertFalse(r.state.refill)
    }

    @Test fun varios_ciclos_no_mesmo_dia() {
        // Lucro antes de cada corrida (o desgaste dos km vai tirando entre elas) e o que ela deixa.
        val rides = listOf(
            150.0 to 60.0, // 210: bate, soma
            208.0 to 25.0, // na meta: adianta
            199.0 to 15.0, // caiu: soma (214) e repõe até 220
            212.0 to 15.0, // 227: ainda repondo, soma
            224.0 to 30.0, // passou de 220: adianta
            218.0 to 30.0, // ≥ meta: adianta (fora do modo de repor, a régua é a meta)
            190.0 to 20.0, // caiu de novo: soma, repõe
            221.0 to 20.0, // passou de 220: adianta
        )
        var s = GoalDayState(day)
        val out = rides.map { (before, ride) ->
            val r = GoalAdvance.decide(before, ride, goal, s)
            s = r.state
            r.divert
        }
        assertEquals(listOf(false, true, false, false, true, true, false, true), out)
    }

    @Test fun dia_novo_comeca_do_zero_e_virada_com_jornada_aberta_fica_no_mesmo_dia() {
        val book = AdvanceBook(state = GoalDayState(day, reached = true, refill = true))
        // Mesmo dia de trabalho (jornada aberta passou da meia-noite): o estado continua.
        assertEquals(GoalDayState(day, true, true), book.stateOf(day))
        // Dia de trabalho seguinte: começa sem meta batida.
        assertEquals(GoalDayState(day + 1), book.stateOf(day + 1))
        // Corrida adiantada depois da meia-noite com a jornada aberta fica no dia da jornada:
        // não é usada no próprio dia, só a partir do seguinte.
        val list = listOf(d(50.0, onDay = day))
        assertEquals(AdvanceDay(0.0, 0.0, 50.0), GoalAdvance.day(list, day, mapOf(day to 120.0)))
        assertEquals(50.0, GoalAdvance.day(list, day + 1, mapOf(day + 1 to 120.0)).used, 1e-9)
    }

    @Test fun adiantamento_paga_os_custos_do_dia_seguinte() {
        val list = listOf(d(60.0, at = 1), d(40.0, at = 2)) // 100 adiantados no dia
        val next = GoalAdvance.day(list, day + 1, mapOf(day to 150.0, day + 1 to 70.0))
        assertEquals(100.0, next.available, 1e-9)
        assertEquals(70.0, next.used, 1e-9)
        assertEquals(30.0, next.left, 1e-9)
        // Lucro do dia seguinte: os 70 de custo saíram do adiantamento.
        assertEquals(250.0, GoalAdvance.net(180.0, next), 1e-9)
    }

    @Test fun adiantamento_acaba_no_meio_do_dia() {
        val list = listOf(d(100.0))
        val next = GoalAdvance.day(list, day + 1, mapOf(day + 1 to 160.0))
        assertEquals(100.0, next.used, 1e-9)
        assertEquals(0.0, next.left, 1e-9)
        // Os 60 que passaram do adiantamento saem do lucro: líquido real 40 → 140.
        assertEquals(140.0, GoalAdvance.net(40.0, next), 1e-9)
        // O que sobrou de um dia passa para o outro.
        val two = GoalAdvance.day(list, day + 2, mapOf(day + 1 to 60.0, day + 2 to 70.0))
        assertEquals(40.0, two.available, 1e-9)
        assertEquals(40.0, two.used, 1e-9)
    }

    @Test fun dia_com_adiantamento_tira_do_lucro() {
        val today = GoalAdvance.day(listOf(d(45.0)), day, emptyMap())
        assertEquals(45.0, today.diverted, 1e-9)
        assertEquals(210.0, GoalAdvance.net(255.0, today), 1e-9)
        assertTrue(GoalAdvance.day(emptyList(), day, mapOf(day to 50.0)).isEmpty)
        assertFalse(today.isEmpty)
    }

    @Test fun corrida_que_saiu_da_jornada_leva_o_adiantamento() {
        val trip = ShiftTrip(1_000L, Platform.UBER, "UberX", 30.0, 1.0, 5.0, finished = true)
        val shift = Shift(startedAt = 0L, trips = listOf(trip))
        val kept = Diversion(1_000L, "UBER", day, 24.0)
        val gone = Diversion(2_000L, "UBER", day, 18.0)
        val otherApp = Diversion(1_000L, "NINETY_NINE", day, 10.0)
        assertEquals(listOf(kept), GoalAdvance.alive(listOf(kept, gone, otherApp), listOf(shift)))
        // Em andamento não conta.
        val open = shift.copy(trips = listOf(trip.copy(finished = false)))
        assertTrue(GoalAdvance.alive(listOf(kept), listOf(open)).isEmpty())
    }

    @Test fun o_que_a_corrida_deixa_tira_os_fixos() {
        assertEquals(40.0, GoalAdvance.share(50.0, 0.2), 1e-9)
        assertEquals(50.0, GoalAdvance.share(50.0, 0.0), 1e-9)
        assertEquals(0.0, GoalAdvance.share(50.0, 1.5), 1e-9)
    }

    @Test fun grava_e_le_de_volta() {
        val book = AdvanceBook(
            diversions = listOf(d(12.5, at = 111), Diversion(222, "INDRIVE", day + 1, 30.0)),
            state = GoalDayState(day + 1, reached = true, refill = true),
        )
        assertEquals(book, AdvanceBook.decode(book.encode()))
        assertEquals(AdvanceBook(), AdvanceBook.decode(null))
        assertEquals(AdvanceBook(), AdvanceBook.decode("lixo"))
        // Linha estragada é ignorada, o resto fica.
        assertEquals(1, AdvanceBook.decode("v1\nd|1|UBER|5|10.00\nd|x|UBER|5|1").diversions.size)
    }

    @Test fun mesma_corrida_nao_repete_e_antigas_saem() {
        var book = GoalAdvance.add(AdvanceBook(), d(10.0, at = 5), day)
        book = GoalAdvance.add(book, d(12.0, at = 5), day)
        assertEquals(listOf(d(12.0, at = 5)), book.diversions)
        book = GoalAdvance.add(book, d(8.0, onDay = day + 70, at = 9), day + 70)
        assertEquals(listOf(d(8.0, onDay = day + 70, at = 9)), book.diversions)
    }

    @Test fun dias_anteriores_desde_o_primeiro_adiantamento() {
        val book = AdvanceBook(diversions = listOf(d(10.0, onDay = day - 3)))
        assertEquals(listOf(day - 3, day - 2, day - 1), GoalAdvance.daysBefore(book, day))
        assertTrue(GoalAdvance.daysBefore(AdvanceBook(), day).isEmpty())
    }

    @Test fun custos_dos_dias_passados_somam_gastos_lancados() {
        val oneDay = 86_400_000L
        val ranges = listOf(DayRange(1, 0, oneDay - 1), DayRange(2, oneDay, 2 * oneDay - 1))
        val entries = listOf(FinanceEntry(kind = EntryKind.EXPENSE, category = "Lavagem", amount = 35.0, atMillis = oneDay + 10))
        val costs = GoalAdvance.pastCosts(ranges, emptyList(), emptyList(), entries, 0.5, 3 * oneDay)
        assertEquals(0.0, costs.getValue(1), 1e-9)
        assertEquals(35.0, costs.getValue(2), 1e-9)
    }
}
