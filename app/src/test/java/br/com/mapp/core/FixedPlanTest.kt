package br.com.mapp.core

import br.com.mapp.core.model.CostUnit
import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.FixedLedger
import br.com.mapp.core.model.KmBase
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.shift.FixedPlan
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Custos fixos rateados no mês: R$ por corrida de hoje = o que falta pagar no mês ÷ corridas que faltam,
 * e o último dia rodado fecha a conta — a soma do mês bate com o total.
 */
class FixedPlanTest {
    private val eps = 1e-6
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val nov = YearMonth.of(2026, 11) // 30 dias

    /** Seguro 300 + financiamento 1.200 = 1.500 no mês; 20 corridas por dia, 30 dias rodados (todo dia). */
    private val total = 1500.0
    private val everyDay = KmBase(kmPerDay = 200.0, daysPerMonth = 30.0, auto = false, ridesPerDay = 20.0)

    private fun at(day: Int, hour: Int, month: YearMonth = nov): Long =
        month.atDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** Jornada do dia [day] com [rides] corridas finalizadas; encerrada com [fixed] por corrida (null = aberta). */
    private fun shift(day: Int, rides: Int, fixed: Double? = null, month: YearMonth = nov, extraOpen: Int = 0): Shift = Shift(
        startedAt = at(day, 8, month),
        gpsKm = rides * 6.0,
        trips = (0 until rides).map { ShiftTrip(at(day, 9, month) + it * 60_000L, Platform.UBER, "UberX", 15.0, 1.0, 4.0) } +
            (0 until extraOpen).map { ShiftTrip(at(day, 19, month), Platform.UBER, "UberX", 15.0, 1.0, 4.0, finished = false) },
        costPerKm = if (fixed != null) 0.9 else null,
        fixedPerRide = fixed,
        endedAt = if (fixed != null) at(day, 20, month) else null,
    )

    /**
     * Roda o mês dia a dia como o app: no fim de cada dia, R$ por corrida = [FixedPlan.today] com as
     * corridas do dia e a jornada encerrada guarda esse valor. Depois fecha o mês ([FixedPlan.settle]).
     */
    private fun runMonth(ridesByDay: List<Int>, base: KmBase, month: YearMonth = nov): Pair<List<Shift>, List<Double>> {
        var history = emptyList<Shift>()
        val values = mutableListOf<Double>()
        ridesByDay.forEachIndexed { i, n ->
            val day = i + 1
            if (n == 0) { values += 0.0; return@forEachIndexed }
            val open = shift(day, n, month = month)
            val plan = FixedPlan.today(total, base, history, open, at(day, 19, month), zone)
            values += plan.perRide
            // Corridas do dia nunca descontam mais do que faltava.
            assertTrue(n * plan.perRide <= plan.remaining + eps)
            history = listOf(shift(day, n, plan.perRide, month)) + history
        }
        return FixedPlan.settle(history, total, month, zone) to values
    }

    private fun fixedOf(list: List<Shift>): Double = list.sumOf { s -> s.trips.count { it.finished } * (s.fixedPerRide ?: 0.0) }

    @Test fun no_ritmo_previsto_o_valor_fica_igual_o_mes_todo_e_soma_o_total() {
        val (closed, values) = runMonth(List(30) { 20 }, everyDay)
        // 1.500 ÷ (20 × 30) = 2,50 todo dia.
        values.forEach { assertEquals(2.5, it, eps) }
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun rodando_menos_que_o_previsto_o_valor_sobe_e_o_mes_bate() {
        // Previsto 20/dia; faz 12/dia.
        val (closed, values) = runMonth(List(30) { 12 }, everyDay)
        assertEquals(2.5, values.first(), eps)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - eps })
        assertTrue(values[28] > 3.0)
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun rodando_mais_que_o_previsto_o_valor_desce_e_nunca_passa_do_total() {
        val (closed, values) = runMonth(List(30) { 30 }, everyDay)
        assertTrue(values.zipWithNext().all { (a, b) -> b <= a + eps })
        assertTrue(values[20] < 2.0)
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun folgas_e_ferias_no_fim_do_mes_o_ultimo_dia_rodado_fecha_a_conta() {
        // Roda 6 dias por semana (26 no mês) e tira férias de 24 a 30.
        val base = everyDay.copy(daysPerMonth = 26.0)
        val days = (1..30).map { d -> if (d >= 24 || d % 7 == 0) 0 else 18 + d % 5 }
        val (closed, values) = runMonth(days, base)
        assertEquals(total, fixedOf(closed), eps)
        // Antes de fechar, o dia 23 tinha o valor do dia; o fechamento sobe só ele.
        val lastDay = closed.first { ShiftMath.sameDay(it.startedAt, at(23, 8), zone) }
        assertTrue(lastDay.fixedPerRide!! > values[22])
        // Dias anteriores não mudam.
        val day22 = closed.first { ShiftMath.sameDay(it.startedAt, at(22, 8), zone) }
        assertEquals(values[21], day22.fixedPerRide!!, eps)
    }

    @Test fun dias_sem_rodar_no_comeco_do_mes_o_que_falta_vai_para_os_dias_que_restam() {
        // Dia 11, nada pago ainda: 1.500 ÷ (20 × 20 dias) = 3,75.
        val plan = FixedPlan.today(total, everyDay, emptyList(), shift(11, 0), at(11, 9), zone)
        assertEquals(0.0, plan.paid, eps)
        assertEquals(20, plan.daysLeft)
        assertEquals(400.0, plan.ridesLeft, eps)
        assertEquals(3.75, plan.perRide, eps)
        // Com 26 dias rodados no mês: hoje + 19 dias × 26/30 = 17,47 dias → 349,3 corridas.
        val p26 = FixedPlan.today(total, everyDay.copy(daysPerMonth = 26.0), emptyList(), null, at(11, 9), zone)
        assertEquals(20.0 * (1 + 26.0 / 30 * 19), p26.ridesLeft, eps)
    }

    @Test fun ja_pago_no_mes_as_proximas_corridas_nao_descontam() {
        val history = listOf(shift(1, 400, 3.75)) // 1.500 já descontados
        val plan = FixedPlan.today(total, everyDay, history, shift(2, 5), at(2, 12), zone)
        assertEquals(0.0, plan.remaining, eps)
        assertEquals(0.0, plan.perRide, eps)
    }

    @Test fun hoje_com_mais_corridas_que_as_que_faltam_divide_pelas_de_hoje() {
        // Dia 30 (último), faltam 100 e o previsto é 20 corridas: com 40 feitas, 100 ÷ 40.
        val history = listOf(shift(29, 560, 2.5)) // 1.400 pagos
        val plan = FixedPlan.today(total, everyDay, history, shift(30, 40), at(30, 18), zone)
        assertEquals(100.0, plan.remaining, eps)
        assertEquals(40.0, plan.ridesLeft, eps)
        assertEquals(2.5, plan.perRide, eps)
    }

    @Test fun jornada_aberta_desde_ontem_conta_no_dia_em_que_comecou() {
        val open = shift(30, 10) // começou dia 30 e já é dia 1 de dezembro
        val now = at(1, 2, YearMonth.of(2026, 12))
        assertEquals(LocalDate.of(2026, 11, 30), FixedPlan.workDate(open, now, zone))
        val plan = FixedPlan.today(total, everyDay, emptyList(), open, now, zone)
        assertEquals(1, plan.daysLeft)
        assertEquals(LocalDate.of(2026, 11, 30), plan.day)
        // Sem jornada aberta, o dia é hoje (calendário).
        assertEquals(LocalDate.of(2026, 12, 1), FixedPlan.workDate(null, now, zone))
    }

    @Test fun so_corridas_finalizadas_do_dia_contam_e_dias_de_outro_mes_nao() {
        val history = listOf(
            shift(31, 50, 9.0, YearMonth.of(2026, 10)), // outubro: fora
            shift(3, 20, 2.0),
        )
        val plan = FixedPlan.today(total, everyDay, history, shift(4, 3, extraOpen = 2), at(4, 12), zone)
        assertEquals(40.0, plan.paid, eps)
        assertEquals(3, plan.ridesToday)
    }

    @Test fun mes_em_que_o_rateio_comecou_paga_so_a_parte_que_faltava() {
        // Começou dia 24: paga 7 de 30 dias = 350.
        val since = LocalDate.of(2026, 11, 24)
        assertEquals(350.0, FixedPlan.monthTarget(total, nov, since), eps)
        assertEquals(total, FixedPlan.monthTarget(total, YearMonth.of(2026, 12), since), eps)
        assertEquals(0.0, FixedPlan.monthTarget(total, YearMonth.of(2026, 10), since), eps)
        // Antes do início não conta como pago (aquelas jornadas não tinham fixo por corrida).
        val history = listOf(shift(20, 20, 0.0), shift(24, 20, 2.5))
        val plan = FixedPlan.today(total, everyDay, history, shift(25, 0), at(25, 9), zone, since)
        assertEquals(350.0, plan.monthTotal, eps)
        assertEquals(50.0, plan.paid, eps)
        assertEquals(300.0 / 120.0, plan.perRide, eps) // 6 dias × 20
        assertEquals(since, plan.startedOn)
        // O mês fecha com 350.
        val closed = FixedPlan.settle(history + shift(30, 10, 1.0), total, nov, zone, since)
        assertEquals(350.0, fixedOf(closed.filter { it.startedAt >= at(24, 0) }), eps)
        assertEquals(0.0, closed.first().fixedPerRide!!, eps) // dia 20 fica como estava
    }

    @Test fun inicio_do_rateio_e_o_primeiro_dia_do_mes_que_ja_descontou_fixo() {
        val history = listOf(shift(24, 20, 3.0), shift(23, 18, 0.0), shift(10, 15, null).copy(costPerKm = 1.0, endedAt = at(10, 20)))
        assertEquals(LocalDate.of(2026, 11, 24), FixedPlan.startDate(history, null, at(25, 9), zone))
        assertEquals(LocalDate.of(2026, 11, 25), FixedPlan.startDate(emptyList(), null, at(25, 9), zone))
        // Fixo descontado só no mês passado: começa hoje.
        assertEquals(LocalDate.of(2026, 12, 2), FixedPlan.startDate(history, null, at(2, 9, YearMonth.of(2026, 12)), zone))
    }

    @Test fun fechar_mes_sem_corridas_nao_muda_nada() {
        val history = listOf(shift(5, 0, 0.0), shift(3, 10, 2.0, YearMonth.of(2026, 12)))
        assertEquals(history, FixedPlan.settle(history, total, nov, zone))
    }

    @Test fun sem_corridas_por_dia_na_base_nao_divide() {
        val plan = FixedPlan.today(total, everyDay.copy(ridesPerDay = 0.0), emptyList(), shift(5, 10), at(5, 12), zone)
        assertEquals(0.0, plan.perRide, eps)
    }

    @Test fun jornadas_do_dia_de_trabalho_recebem_o_valor_novo() {
        val ontem = shift(29, 10, 2.0)
        val hoje = shift(30, 8, 2.0)
        val out = ShiftMath.withCostOn(listOf(ontem, hoje), LocalDate.of(2026, 11, 30), 1.1, zone, null, 3.0)
        assertEquals(2.0, out[0].fixedPerRide!!, eps)
        assertEquals(0.9, out[0].costPerKm!!, eps)
        assertEquals(3.0, out[1].fixedPerRide!!, eps)
        assertEquals(1.1, out[1].costPerKm!!, eps)
    }

    @Test fun custo_do_veiculo_usa_o_valor_do_rateio() {
        val cfg = VehicleCostConfig(
            extraCosts = listOf(ExtraKmCost("Seguro", 300.0, CostUnit.FIXED_MONTH), ExtraKmCost("Parcela", 1200.0, CostUnit.FIXED_MONTH)),
            kmBase = everyDay,
        )
        assertEquals(2.5, cfg.fixedPerRide, eps)                    // antes do 1º rateio: total ÷ corridas do mês
        val ratedToday = cfg.copy(ledger = FixedLedger(today = 3.1))
        assertEquals(3.1, ratedToday.fixedPerRide, eps)
        assertEquals(2.5, ratedToday.evenFixedPerRide, eps)
        assertEquals(10 * ratedToday.costPerKm + 3.1, ratedToday.rideCost(10.0), eps)
        // Sem custo fixo, nada por corrida, mesmo com valor antigo guardado.
        assertEquals(0.0, VehicleCostConfig(ledger = FixedLedger(today = 3.1)).fixedPerRide, eps)
        assertNull(VehicleCostConfig().ledger.today)
    }
}
