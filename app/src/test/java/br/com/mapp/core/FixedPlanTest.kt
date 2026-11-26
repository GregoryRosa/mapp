package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.CostUnit
import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.FixedLedger
import br.com.mapp.core.model.KmBase
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
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
 * Custos fixos rateados no mês pelo valor das corridas: % de hoje = o que falta pagar no mês ÷
 * faturamento que ainda deve entrar, e o último dia rodado fecha a conta — a soma do mês bate com o total.
 */
class FixedPlanTest {
    private val eps = 1e-6
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val nov = YearMonth.of(2026, 11) // 30 dias

    /** Seguro 300 + financiamento 1.200 = 1.500 no mês; R$ 200 por dia, 30 dias rodados = 6.000 → 25%. */
    private val total = 1500.0
    private val everyDay = KmBase(kmPerDay = 200.0, daysPerMonth = 30.0, auto = false, grossPerDay = 200.0)

    /** Valor de cada corrida de teste: 20 corridas = R$ 200 (um dia no ritmo previsto). */
    private val price = 10.0

    private fun at(day: Int, hour: Int, month: YearMonth = nov): Long =
        month.atDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** Jornada do dia [day] com [rides] corridas finalizadas de R$ 10; encerrada com [share] (null = aberta). */
    private fun shift(day: Int, rides: Int, share: Double? = null, month: YearMonth = nov, extraOpen: Int = 0): Shift = Shift(
        startedAt = at(day, 8, month),
        gpsKm = rides * 6.0,
        trips = (0 until rides).map { ShiftTrip(at(day, 9, month) + it * 60_000L, Platform.UBER, "UberX", price, 1.0, 4.0) } +
            (0 until extraOpen).map { ShiftTrip(at(day, 19, month), Platform.UBER, "UberX", price, 1.0, 4.0, finished = false) },
        costPerKm = if (share != null) 0.9 else null,
        fixedShare = share,
        endedAt = if (share != null) at(day, 20, month) else null,
    )

    /** Jornada encerrada por uma versão anterior, com o fixo gravado em R$ por corrida. */
    private fun legacy(day: Int, rides: Int, perRide: Double): Shift =
        shift(day, rides, 0.0).copy(fixedShare = null, fixedPerRide = perRide)

    /**
     * Roda o mês dia a dia como o app: no fim de cada dia, a % = [FixedPlan.today] com as corridas do
     * dia e a jornada encerrada guarda essa %. Depois fecha o mês ([FixedPlan.settle]).
     */
    private fun runMonth(ridesByDay: List<Int>, base: KmBase, month: YearMonth = nov): Pair<List<Shift>, List<Double>> {
        var history = emptyList<Shift>()
        val values = mutableListOf<Double>()
        ridesByDay.forEachIndexed { i, n ->
            val day = i + 1
            if (n == 0) { values += 0.0; return@forEachIndexed }
            val open = shift(day, n, month = month)
            val plan = FixedPlan.today(total, base, history, open, at(day, 19, month), zone)
            values += plan.share
            // O faturamento do dia nunca desconta mais do que faltava.
            assertTrue(open.finishedGross * plan.share <= plan.remaining + eps)
            history = listOf(shift(day, n, plan.share, month)) + history
        }
        return FixedPlan.settle(history, total, month, zone) to values
    }

    /** O que as jornadas descontaram de custo fixo (a regra do resumo da jornada). */
    private fun fixedOf(list: List<Shift>): Double = list.sumOf { ShiftMath.fixedCostOf(it) }

    @Test fun no_ritmo_previsto_a_porcentagem_fica_igual_o_mes_todo_e_soma_o_total() {
        val (closed, values) = runMonth(List(30) { 20 }, everyDay)
        // 1.500 ÷ (R$ 200 × 30) = 25% todo dia.
        values.forEach { assertEquals(0.25, it, eps) }
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun faturando_menos_que_o_previsto_a_porcentagem_sobe_e_o_mes_bate() {
        // Previsto R$ 200/dia; fatura R$ 120/dia.
        val (closed, values) = runMonth(List(30) { 12 }, everyDay)
        assertEquals(0.25, values.first(), eps)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - eps })
        assertTrue(values[28] > 0.30)
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun faturando_mais_que_o_previsto_a_porcentagem_desce_e_nunca_passa_do_total() {
        val (closed, values) = runMonth(List(30) { 30 }, everyDay)
        assertTrue(values.zipWithNext().all { (a, b) -> b <= a + eps })
        assertTrue(values[20] < 0.20)
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun folgas_e_ferias_no_fim_do_mes_o_ultimo_dia_rodado_fecha_a_conta() {
        // Roda 6 dias por semana (26 no mês) e tira férias de 24 a 30.
        val base = everyDay.copy(daysPerMonth = 26.0)
        val days = (1..30).map { d -> if (d >= 24 || d % 7 == 0) 0 else 18 + d % 5 }
        val (closed, values) = runMonth(days, base)
        assertEquals(total, fixedOf(closed), eps)
        // Antes de fechar, o dia 23 tinha a % do dia; o fechamento sobe só ele.
        val lastDay = closed.first { ShiftMath.sameDay(it.startedAt, at(23, 8), zone) }
        assertTrue(lastDay.fixedShare!! > values[22])
        assertNull(lastDay.fixedPerRide)
        // Dias anteriores não mudam.
        val day22 = closed.first { ShiftMath.sameDay(it.startedAt, at(22, 8), zone) }
        assertEquals(values[21], day22.fixedShare!!, eps)
    }

    @Test fun dias_sem_rodar_no_comeco_do_mes_o_que_falta_vai_para_os_dias_que_restam() {
        // Dia 11, nada pago ainda: 1.500 ÷ (R$ 200 × 20 dias) = 37,5%.
        val plan = FixedPlan.today(total, everyDay, emptyList(), shift(11, 0), at(11, 9), zone)
        assertEquals(0.0, plan.paid, eps)
        assertEquals(20, plan.daysLeft)
        assertEquals(4000.0, plan.grossLeft, eps)
        assertEquals(0.375, plan.share, eps)
        // Com 26 dias rodados no mês: hoje + 19 dias × 26/30 = 17,47 dias de R$ 200.
        val p26 = FixedPlan.today(total, everyDay.copy(daysPerMonth = 26.0), emptyList(), null, at(11, 9), zone)
        assertEquals(200.0 * (1 + 26.0 / 30 * 19), p26.grossLeft, eps)
    }

    @Test fun ja_pago_no_mes_as_proximas_corridas_nao_descontam() {
        val history = listOf(shift(1, 600, 0.25)) // R$ 6.000 × 25% = 1.500 já descontados
        val plan = FixedPlan.today(total, everyDay, history, shift(2, 5), at(2, 12), zone)
        assertEquals(1500.0, plan.paid, eps)
        assertEquals(0.0, plan.remaining, eps)
        assertEquals(0.0, plan.share, eps)
    }

    @Test fun hoje_faturou_mais_que_o_previsto_divide_pelo_bruto_de_hoje() {
        // Dia 30 (último), faltam 100 e o previsto é R$ 200: com R$ 500 feitos, 100 ÷ 500 = 20%.
        val history = listOf(shift(29, 560, 0.25)) // R$ 5.600 × 25% = 1.400 pagos
        val plan = FixedPlan.today(total, everyDay, history, shift(30, 50), at(30, 18), zone)
        assertEquals(100.0, plan.remaining, eps)
        assertEquals(500.0, plan.grossToday, eps)
        assertEquals(500.0, plan.grossLeft, eps)
        assertEquals(0.20, plan.share, eps)
    }

    @Test fun jornada_aberta_desde_ontem_conta_no_dia_em_que_comecou() {
        val open = shift(30, 10) // começou dia 30 e já é dia 1 de dezembro
        val now = at(1, 2, YearMonth.of(2026, 12))
        assertEquals(LocalDate.of(2026, 11, 30), FixedPlan.workDate(open, now, zone))
        val plan = FixedPlan.today(total, everyDay, emptyList(), open, now, zone)
        assertEquals(1, plan.daysLeft)
        assertEquals(LocalDate.of(2026, 11, 30), plan.day)
        assertEquals(LocalDate.of(2026, 11, 30), plan.monthEnd)
        assertEquals(100.0, plan.grossToday, eps)
        // Sem jornada aberta, o dia é hoje (calendário).
        assertEquals(LocalDate.of(2026, 12, 1), FixedPlan.workDate(null, now, zone))
    }

    @Test fun so_corridas_finalizadas_do_dia_contam_e_dias_de_outro_mes_nao() {
        val history = listOf(
            shift(31, 50, 0.9, YearMonth.of(2026, 10)), // outubro: fora
            shift(3, 20, 0.2), // R$ 200 × 20% = 40
        )
        val plan = FixedPlan.today(total, everyDay, history, shift(4, 3, extraOpen = 2), at(4, 12), zone)
        assertEquals(40.0, plan.paid, eps)
        assertEquals(30.0, plan.grossToday, eps) // 3 finalizadas; as 2 em andamento não
    }

    @Test fun jornadas_antigas_por_corrida_contam_o_que_pagaram() {
        // Dias 1 e 2 gravados pela versão anterior (R$ 2,50 por corrida), dia 3 já em %.
        val history = listOf(legacy(1, 20, 2.5), legacy(2, 20, 2.5), shift(3, 20, 0.25))
        val plan = FixedPlan.today(total, everyDay, history, shift(4, 0), at(4, 9), zone)
        assertEquals(50.0 + 50.0 + 50.0, plan.paid, eps)
        assertEquals(1350.0 / (200.0 * 27), plan.share, eps)
        // No resumo da jornada o legado continua: corridas finalizadas × R$ por corrida.
        assertEquals(50.0, ShiftMath.summary(history[0], 0.9, at(1, 21), 0.9).fixedCost, eps)
    }

    @Test fun mes_em_que_o_rateio_comecou_paga_so_a_parte_que_faltava() {
        // Começou dia 24: paga 7 de 30 dias = 350.
        val since = LocalDate.of(2026, 11, 24)
        assertEquals(350.0, FixedPlan.monthTarget(total, nov, since), eps)
        assertEquals(total, FixedPlan.monthTarget(total, YearMonth.of(2026, 12), since), eps)
        assertEquals(0.0, FixedPlan.monthTarget(total, YearMonth.of(2026, 10), since), eps)
        // Antes do início não conta como pago (aquelas jornadas não tinham fixo).
        val history = listOf(shift(20, 20, 0.0), shift(24, 20, 0.25))
        val plan = FixedPlan.today(total, everyDay, history, shift(25, 0), at(25, 9), zone, since)
        assertEquals(350.0, plan.monthTotal, eps)
        assertEquals(50.0, plan.paid, eps)
        assertEquals(300.0 / 1200.0, plan.share, eps) // 6 dias × R$ 200
        assertEquals(since, plan.startedOn)
        // O mês fecha com 350.
        val closed = FixedPlan.settle(history + shift(30, 10, 0.1), total, nov, zone, since)
        assertEquals(350.0, fixedOf(closed.filter { it.startedAt >= at(24, 0) }), eps)
        assertEquals(0.0, closed.first().fixedShare!!, eps) // dia 20 fica como estava
    }

    @Test fun inicio_do_rateio_e_o_primeiro_dia_do_mes_que_ja_descontou_fixo() {
        val history = listOf(shift(24, 20, 0.3), shift(23, 18, 0.0), shift(10, 15, null).copy(costPerKm = 1.0, endedAt = at(10, 20)))
        assertEquals(LocalDate.of(2026, 11, 24), FixedPlan.startDate(history, null, at(25, 9), zone))
        assertEquals(LocalDate.of(2026, 11, 25), FixedPlan.startDate(emptyList(), null, at(25, 9), zone))
        // Jornada antiga com R$ por corrida também marca o início.
        assertEquals(LocalDate.of(2026, 11, 22), FixedPlan.startDate(history + legacy(22, 10, 2.0), null, at(25, 9), zone))
        // Fixo descontado só no mês passado: começa hoje.
        assertEquals(LocalDate.of(2026, 12, 2), FixedPlan.startDate(history, null, at(2, 9, YearMonth.of(2026, 12)), zone))
    }

    @Test fun fechar_mes_sem_corridas_nao_muda_nada() {
        val history = listOf(shift(5, 0, 0.0), shift(3, 10, 0.2, YearMonth.of(2026, 12)))
        assertEquals(history, FixedPlan.settle(history, total, nov, zone))
    }

    @Test fun fechar_mes_com_ultimo_dia_antigo_troca_para_porcentagem() {
        // Mês todo pela versão anterior, R$ 2,00 por corrida: 29 dias × 20 corridas = 1.160 pagos.
        val history = (1..29).map { legacy(it, 20, 2.0) }
        val closed = FixedPlan.settle(history, total, nov, zone)
        val last = closed.first { ShiftMath.sameDay(it.startedAt, at(29, 8), zone) }
        assertNull(last.fixedPerRide)
        assertEquals((total - 28 * 40.0) / 200.0, last.fixedShare!!, eps)
        assertEquals(total, fixedOf(closed), eps)
    }

    @Test fun sem_faturamento_na_base_nao_divide() {
        val plan = FixedPlan.today(total, everyDay.copy(grossPerDay = 0.0), emptyList(), shift(5, 10), at(5, 12), zone)
        assertEquals(0.0, plan.grossLeft, eps)
        assertEquals(0.0, plan.share, eps)
    }

    @Test fun jornadas_do_dia_de_trabalho_recebem_a_porcentagem_nova() {
        val ontem = shift(29, 10, 0.2)
        val hoje = legacy(30, 8, 2.0) // gravada por corrida: passa a ser % do valor
        val out = ShiftMath.withCostOn(listOf(ontem, hoje), LocalDate.of(2026, 11, 30), 1.1, zone, null, 0.3)
        assertEquals(0.2, out[0].fixedShare!!, eps)
        assertEquals(0.9, out[0].costPerKm!!, eps)
        assertEquals(0.3, out[1].fixedShare!!, eps)
        assertNull(out[1].fixedPerRide)
        assertEquals(1.1, out[1].costPerKm!!, eps)
        assertEquals(80.0 * 0.3, ShiftMath.summary(out[1], 0.0, at(30, 21)).fixedCost, eps)
    }

    @Test fun custo_do_veiculo_usa_a_porcentagem_do_rateio() {
        val cfg = VehicleCostConfig(
            extraCosts = listOf(ExtraKmCost("Seguro", 300.0, CostUnit.FIXED_MONTH), ExtraKmCost("Parcela", 1200.0, CostUnit.FIXED_MONTH)),
            kmBase = everyDay,
        )
        assertEquals(6000.0, everyDay.monthGross, eps)
        assertEquals(0.25, cfg.fixedShare, eps) // antes do 1º rateio: total ÷ faturamento do mês
        val ratedToday = cfg.copy(ledger = FixedLedger(share = 0.31))
        assertEquals(0.31, ratedToday.fixedShare, eps)
        assertEquals(0.25, ratedToday.evenFixedShare, eps)
        assertEquals(12.0 * 0.31, ratedToday.fixedCostOf(12.0), eps)
        assertEquals(10 * ratedToday.costPerKm + 40.0 * 0.31, ratedToday.rideCost(10.0, 40.0), eps)
        // Sem custo fixo, nada sai do valor, mesmo com % antiga guardada.
        assertEquals(0.0, VehicleCostConfig(ledger = FixedLedger(share = 0.31)).fixedShare, eps)
        assertNull(VehicleCostConfig().ledger.share)
    }

    @Test fun corrida_curta_paga_menos_fixo_que_a_longa() {
        // 25% do valor: a de R$ 12 com 2 km paga R$ 3; a de R$ 50 com 10 km paga R$ 12,50.
        val cfg = VehicleCostConfig(
            fuelPricePerUnit = 6.0, kmPerUnit = 12.0, maintenance = 0.2, depreciation = 0.2,
            extraCosts = listOf(ExtraKmCost("Seguro", 1500.0, CostUnit.FIXED_MONTH)),
            kmBase = everyDay,
        )
        val curta = RawOffer(Platform.UBER, 12.0, tripKm = 1.5, tripMin = 5, pickupKm = 0.5, pickupMin = 2)
        val longa = RawOffer(Platform.UBER, 50.0, tripKm = 9.0, tripMin = 20, pickupKm = 1.0, pickupMin = 3)
        assertEquals(3.0, cfg.fixedCostOf(curta.price), eps)
        assertEquals(12.5, cfg.fixedCostOf(longa.price), eps)
        // Lucro = valor − desgaste (0,90/km) − 25% do valor.
        assertEquals(12.0 - 2 * 0.9 - 3.0, OfferEvaluator.metrics(curta, cfg).netProfit!!, eps)
        assertEquals(50.0 - 10 * 0.9 - 12.5, OfferEvaluator.metrics(longa, cfg).netProfit!!, eps)
        // A curta (R$ 6/km) continua com lucro por km melhor que a longa (R$ 5/km).
        assertTrue(OfferEvaluator.metrics(curta, cfg).netPerKm!! > OfferEvaluator.metrics(longa, cfg).netPerKm!!)
    }
}
