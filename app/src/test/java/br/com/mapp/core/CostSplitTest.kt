package br.com.mapp.core

import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.shift.CostAmount
import br.com.mapp.core.shift.CostPart
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftCodec
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.model.Platform
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CostSplitTest {
    private val eps = 1e-6
    private val h = 3_600_000L

    /** Gasolina R$ 6,00 / 12 km/l = 0,50/km + manutenção 0,15 + depreciação 0,20 + pneus 0,05 = 0,90/km. */
    private val cfg = VehicleCostConfig(
        fuelType = FuelType.GASOLINE,
        fuelPricePerUnit = 6.0,
        kmPerUnit = 12.0,
        maintenance = 0.15,
        depreciation = 0.20,
        extraCosts = listOf(ExtraKmCost("Pneus", 0.05)),
    )
    private val parts = CostSplit.parts(cfg)

    private fun assertAmounts(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, eps) }
    }

    @Test fun itens_dos_custos_fixos_na_ordem_da_tela() {
        assertEquals(listOf("Combustível", "Manutenção", "Depreciação", "Pneus"), parts.map { it.name })
        assertEquals(cfg.costPerKm, parts.sumOf { it.perKm }, eps)
        val eletrico = CostSplit.parts(cfg.copy(fuelType = FuelType.ELECTRIC, extraCosts = listOf(ExtraKmCost("  ", 0.1))))
        assertEquals(listOf("Energia", "Manutenção", "Depreciação", "Outro custo"), eletrico.map { it.name })
    }

    @Test fun divide_o_custo_na_proporcao_do_rs_por_km() {
        val out = CostSplit.split(90.0, parts) // 100 km × 0,90
        assertAmounts(listOf(50.0, 15.0, 20.0, 5.0), out.map { it.amount })
        // Sem item com valor: tudo em "Custo do carro".
        assertEquals(listOf(CostAmount("Custo do carro", 10.0)), CostSplit.split(10.0, listOf(CostPart("Pneus", 0.0))))
        assertTrue(CostSplit.split(0.0, parts).isEmpty())
    }

    @Test fun jornada_com_divisao_gravada_usa_a_dela_e_antiga_usa_a_de_hoje() {
        val zone = ZoneId.of("America/Sao_Paulo")
        fun ms(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        // Em outubro o combustível era mais caro: 0,60/km (total 1,00/km) e a jornada gravou isso.
        val gravada = Shift(
            startedAt = ms(10, 8), activeMs = 8 * h, resumedAt = null, gpsKm = 100.0, endedAt = ms(10, 16),
            costPerKm = 1.0,
            costParts = listOf(CostPart("Combustível", 0.6), CostPart("Manutenção", 0.15), CostPart("Depreciação", 0.2), CostPart("Pneus", 0.05)),
            trips = listOf(ShiftTrip(ms(10, 9), Platform.UBER, "UberX", 250.0, 1.0, 10.0)),
        )
        val t = PeriodMath.totals(ms(1, 0), ms(31, 23), null, listOf(gravada), emptyList(), emptyList(), cfg.costPerKm, ms(31, 23), parts)
        assertEquals(100.0, t.carCost, eps)
        assertAmounts(listOf(60.0, 15.0, 20.0, 5.0), t.carCostItems.map { it.amount })
        assertFalse(t.splitEstimated)

        // Jornada de versão antiga: só o total (1,00/km); dividido pelas proporções de hoje (0,90/km).
        val antiga = gravada.copy(costParts = null)
        val e = PeriodMath.totals(ms(1, 0), ms(31, 23), null, listOf(antiga), emptyList(), emptyList(), cfg.costPerKm, ms(31, 23), parts)
        assertEquals(100.0, e.carCostItems.sumOf { it.amount }, eps)
        assertEquals(100.0 * 0.5 / 0.9, e.carCostItems.first().amount, eps)
        assertTrue(e.splitEstimated)

        // Antiga com o mesmo total de hoje: a divisão de hoje vale, sem aviso.
        val igual = antiga.copy(costPerKm = cfg.costPerKm)
        assertFalse(PeriodMath.totals(ms(1, 0), ms(31, 23), null, listOf(igual), emptyList(), emptyList(), cfg.costPerKm, ms(31, 23), parts).splitEstimated)
    }

    @Test fun quebra_do_bruto_com_gastos_lancados_e_sobra() {
        val zone = ZoneId.of("America/Sao_Paulo")
        fun ms(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        val shift = Shift(
            startedAt = ms(5, 8), activeMs = 8 * h, resumedAt = null, gpsKm = 200.0, endedAt = ms(5, 16),
            costPerKm = cfg.costPerKm, costParts = parts,
            trips = listOf(ShiftTrip(ms(5, 9), Platform.UBER, "UberX", 500.0, 2.0, 30.0)),
        )
        val entries = listOf(
            FinanceEntry(kind = EntryKind.EXPENSE, category = "Alimentação", amount = 30.0, atMillis = ms(5, 12)),
            FinanceEntry(kind = EntryKind.EXPENSE, category = "Combustível", amount = 50.0, atMillis = ms(5, 13)),
            FinanceEntry(kind = EntryKind.EXPENSE, category = "Alimentação", amount = 20.0, atMillis = ms(6, 12)),
        )
        val t = PeriodMath.totals(ms(1, 0), ms(30, 23), null, listOf(shift), emptyList(), entries, cfg.costPerKm, ms(30, 23), parts)
        val b = CostSplit.breakdown(t)

        assertEquals(500.0, b.gross, eps)
        // 200 km × 0,50 = 100 → 20% do bruto.
        assertEquals("Combustível", b.car[0].name)
        assertEquals(100.0, b.car[0].amount, eps)
        assertEquals(20.0, b.car[0].percent!!, eps)
        assertAmounts(listOf(100.0, 30.0, 40.0, 10.0), b.car.map { it.amount })
        // Gastos por categoria, maior primeiro; total 100 = 20%.
        assertEquals(listOf("Alimentação", "Combustível"), b.expenses.map { it.name })
        assertAmounts(listOf(50.0, 50.0), b.expenses.map { it.amount })
        assertEquals(100.0, b.expensesTotal!!.amount, eps)
        assertEquals(20.0, b.expensesTotal!!.percent!!, eps)
        // Combustível lançado conta duas vezes: aparece para o aviso.
        assertEquals(50.0, b.fuelLaunched, eps)
        // Sobra: 500 − 180 − 100 = 220 = 44%; as fatias somam 100%.
        assertEquals(220.0, b.net.amount, eps)
        assertEquals(44.0, b.net.percent!!, eps)
        val sum = b.car.sumOf { it.percent!! } + b.expensesTotal!!.percent!! + b.net.percent!!
        assertEquals(100.0, sum, eps)
    }

    @Test fun sem_bruto_nao_tem_porcentagem_e_sem_itens_vira_custo_do_carro() {
        val t = PeriodTotals(km = 10.0, carCost = 9.0, expenses = 5.0, expenseItems = listOf(CostAmount("Lavagem", 5.0)))
        val b = CostSplit.breakdown(t)
        assertEquals(listOf("Custo do carro"), b.car.map { it.name })
        assertNull(b.car[0].percent)
        assertNull(b.net.percent)
        assertEquals(-14.0, b.net.amount, eps)
        assertEquals(0.0, b.fuelLaunched, eps)
        assertTrue(CostSplit.breakdown(PeriodTotals()).isEmpty)
    }

    @Test fun categorias_de_combustivel_lancado() {
        assertTrue(CostSplit.isFuel("Combustível"))
        assertTrue(CostSplit.isFuel(" combustivel "))
        assertTrue(CostSplit.isFuel("Recarga"))
        assertFalse(CostSplit.isFuel("Lavagem"))
    }

    @Test fun divisao_gravada_na_jornada_e_lida_de_volta() {
        val s = Shift(startedAt = 1000, activeMs = 5, resumedAt = null, gpsKm = 12.5, costPerKm = 0.9, endedAt = 2000,
            costParts = listOf(CostPart("Combustível", 0.5), CostPart("Seguro\tcarro", 0.07)))
        val back = ShiftCodec.decode(ShiftCodec.encode(s))!!
        assertEquals(listOf(CostPart("Combustível", 0.5), CostPart("Seguro carro", 0.07)), back.costParts)
        assertEquals(0.9, back.costPerKm!!, eps)
        assertEquals(2000L, back.endedAt)
        // Gravação antiga (7 campos) e jornada sem divisão: null.
        assertNull(ShiftCodec.decode("v1\t1000\t5\t\t12.5\t0.9\t2000")!!.costParts)
        assertNull(ShiftCodec.decode(ShiftCodec.encode(s.copy(costParts = null)))!!.costParts)
        // Lista com várias jornadas continua separando certo.
        val list = ShiftCodec.decodeList(ShiftCodec.encodeList(listOf(s, s.copy(startedAt = 3000))))
        assertEquals(2, list.size)
        assertEquals(list[0].costParts, list[1].costParts)
    }

    @Test fun custo_novo_de_hoje_leva_a_divisao_junto() {
        val zone = ZoneId.of("America/Sao_Paulo")
        fun ms(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        val hoje = Shift(startedAt = ms(13, 6), gpsKm = 100.0, costPerKm = 0.85, endedAt = ms(13, 11))
        val ontem = Shift(startedAt = ms(12, 6), gpsKm = 80.0, costPerKm = 0.80, endedAt = ms(12, 12), costParts = listOf(CostPart("X", 0.8)))
        val out = ShiftMath.withCostToday(listOf(hoje, ontem), cfg.costPerKm, ms(13, 16), zone, parts)
        assertEquals(parts, out[0].costParts)
        assertEquals(listOf(CostPart("X", 0.8)), out[1].costParts)
    }
}
