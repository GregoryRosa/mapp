package br.com.mapp.core

import br.com.mapp.core.model.CostUnit
import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.ExtraKmCostCodec
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.model.KmBase
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.KmAverages
import br.com.mapp.core.shift.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VehicleCostTest {
    @Test fun eletrico_usa_preco_do_kwh_e_km_por_kwh() {
        val c = VehicleCostConfig(FuelType.ELECTRIC, fuelPricePerUnit = 0.90, kmPerUnit = 6.0, maintenance = 0.05, depreciation = 0.25)
        assertEquals(0.15, c.energyCostPerKm, 1e-9)
        assertEquals(0.45, c.costPerKm, 1e-9)
        assertEquals("R$/kWh", FuelType.ELECTRIC.priceSuffix)
        assertEquals("km/kWh", FuelType.ELECTRIC.consumptionSuffix)
    }

    @Test fun combustiveis_liquidos_usam_litro() {
        listOf(FuelType.GASOLINE, FuelType.ETHANOL, FuelType.DIESEL).forEach {
            assertEquals("R$/l", it.priceSuffix)
            assertEquals("km/l", it.consumptionSuffix)
        }
        val etanol = VehicleCostConfig(FuelType.ETHANOL, 4.20, 7.0, 0.0, 0.0)
        assertEquals(0.60, etanol.costPerKm, 1e-9)
    }

    @Test fun custos_extras_somam_no_custo_por_km() {
        val c = VehicleCostConfig(
            FuelType.GASOLINE, 6.0, 12.0, 0.10, 0.20,
            extraCosts = listOf(ExtraKmCost("Pneus", 0.04), ExtraKmCost("Seguro", 0.06)),
        )
        assertEquals(0.10, c.extraPerKm, 1e-9)
        assertEquals(0.90, c.costPerKm, 1e-9)
        assertEquals(0.80, c.copy(extraCosts = emptyList()).costPerKm, 1e-9)
    }

    @Test fun codec_de_custos_extras_ida_e_volta() {
        val list = listOf(ExtraKmCost("Pneus", 0.04), ExtraKmCost("Aluguel\tdo carro", 0.5))
        val back = ExtraKmCostCodec.decode(ExtraKmCostCodec.encode(list))
        assertEquals(listOf(ExtraKmCost("Pneus", 0.04), ExtraKmCost("Aluguel do carro", 0.5)), back)
        assertEquals(emptyList<ExtraKmCost>(), ExtraKmCostCodec.decode(null))
        assertEquals(emptyList<ExtraKmCost>(), ExtraKmCostCodec.decode(ExtraKmCostCodec.encode(emptyList())))
        // Linha inválida não derruba as outras.
        assertEquals(listOf(ExtraKmCost("IPVA", 0.03)), ExtraKmCostCodec.decode("lixo\nv1\tX\tabc\nv1\tIPVA\t0.03"))
    }

    @Test fun consumo_zero_nao_divide_por_zero() {
        assertEquals(0.0, VehicleCostConfig(FuelType.DIESEL, 6.0, 0.0, 0.0, 0.0).energyCostPerKm, 1e-9)
    }

    // --- Custos digitados pelo total do mês ---

    private val base = KmBase(kmPerDay = 200.0, daysPerMonth = 25.0, auto = false) // 5.000 km/mês

    @Test fun custo_do_mes_vira_rs_por_km_pela_base() {
        assertEquals(5000.0, base.monthKm, 1e-9)
        assertEquals(0.06, base.perKm(300.0, CostUnit.PER_MONTH), 1e-9)
        assertEquals(0.06, base.perKm(0.06, CostUnit.PER_KM), 1e-9)
        assertEquals(300.0, base.perMonth(0.06, CostUnit.PER_KM), 1e-9)
        // Sem km na base o custo do mês não vira R$/km (nada de dividir por zero).
        assertEquals(0.0, KmBase(0.0, 25.0).perKm(300.0, CostUnit.PER_MONTH), 1e-9)
    }

    @Test fun custos_do_mes_somam_no_custo_por_km_e_na_divisao() {
        val c = VehicleCostConfig(
            FuelType.GASOLINE, 6.0, 12.0,
            maintenance = 500.0, depreciation = 0.20,
            extraCosts = listOf(ExtraKmCost("Seguro", 250.0, CostUnit.PER_MONTH), ExtraKmCost("Pneus", 0.04)),
            maintenanceUnit = CostUnit.PER_MONTH,
            kmBase = base,
        )
        assertEquals(0.10, c.maintenancePerKm, 1e-9) // 500 / 5.000
        assertEquals(0.20, c.depreciationPerKm, 1e-9)
        assertEquals(0.05, c.perKmOf(c.extraCosts[0]), 1e-9) // 250 / 5.000
        assertEquals(0.09, c.extraPerKm, 1e-9)
        assertEquals(0.89, c.costPerKm, 1e-9) // 0,50 + 0,10 + 0,20 + 0,09
        assertTrue(c.usesMonthly)
        assertFalse(VehicleCostConfig().usesMonthly)
        val parts = CostSplit.parts(c)
        assertEquals(listOf(0.50, 0.10, 0.20, 0.05, 0.04), parts.map { it.perKm }.map { Math.round(it * 100) / 100.0 })
        assertEquals(c.costPerKm, parts.sumOf { it.perKm }, 1e-9)
        // Rodando mais no mês, o mesmo custo do mês pesa menos por km.
        val more = c.copy(kmBase = base.copy(kmPerDay = 250.0))
        assertEquals(0.08, more.maintenancePerKm, 1e-9)
    }

    @Test fun codec_guarda_a_unidade_e_le_a_versao_antiga_como_por_km() {
        val list = listOf(ExtraKmCost("Seguro", 250.0, CostUnit.PER_MONTH), ExtraKmCost("Pneus", 0.04))
        assertEquals(list, ExtraKmCostCodec.decode(ExtraKmCostCodec.encode(list)))
        assertEquals(listOf(ExtraKmCost("IPVA", 0.03, CostUnit.PER_KM)), ExtraKmCostCodec.decode("v1\tIPVA\t0.03"))
        assertEquals(emptyList<ExtraKmCost>(), ExtraKmCostCodec.decode("v2\tX\t10\tPOR_ANO"))
    }

    // --- Média de km das jornadas ---

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val today: LocalDate = LocalDate.of(2026, 11, 19)
    private val now = at(today, 20)

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun shift(daysAgo: Long, km: Double, hour: Int = 8) = Shift(startedAt = at(today.minusDays(daysAgo), hour), gpsKm = km)

    @Test fun media_por_dia_rodado_e_dias_no_mes_pelo_ritmo() {
        // 30 dias, roda 6 e folga 1: 26 dias rodados (dias 0..29 menos os múltiplos de 7 a partir de 6).
        val shifts = (0L until 30L).filter { it % 7 != 6L }.map { shift(it, 180.0) }
        val avg = KmAverages.of(shifts, now, zone)!!
        assertEquals(180.0, avg.kmPerDay, 1e-9)
        assertEquals(26, avg.workedDays)
        assertEquals(30, avg.windowDays)
        assertEquals(26.0, avg.daysPerMonth!!, 1e-9)
        val applied = KmAverages.applied(KmBase(), avg)
        assertEquals(180.0 * 26, applied.monthKm, 1e-9)
    }

    @Test fun duas_jornadas_no_mesmo_dia_somam_e_dia_sem_km_nao_conta() {
        val shifts = listOf(shift(1, 120.0, 7), shift(1, 80.0, 18), shift(2, 100.0), shift(3, 0.4))
        val avg = KmAverages.of(shifts, now, zone)!!
        assertEquals(150.0, avg.kmPerDay, 1e-9) // (200 + 100) / 2
        assertEquals(2, avg.workedDays)
        assertEquals(3, avg.windowDays) // de anteontem até hoje
        assertNull(avg.daysPerMonth) // pouco histórico para saber as folgas
        // Sem os dias do mês, fica o que já estava na base.
        val applied = KmAverages.applied(KmBase(kmPerDay = 90.0, daysPerMonth = 22.0), avg)
        assertEquals(150.0, applied.kmPerDay, 1e-9)
        assertEquals(22.0, applied.daysPerMonth, 1e-9)
    }

    @Test fun so_os_ultimos_30_dias_e_base_digitada_nao_muda() {
        assertNull(KmAverages.of(listOf(shift(30, 300.0)), now, zone))
        assertNull(KmAverages.of(emptyList(), now, zone))
        val avg = KmAverages.of(listOf(shift(29, 300.0), shift(0, 100.0)), now, zone)!!
        assertEquals(200.0, avg.kmPerDay, 1e-9)
        assertEquals(2.0, avg.daysPerMonth!!, 1e-9)
        val typed = KmBase(kmPerDay = 210.0, daysPerMonth = 24.0, auto = false)
        assertEquals(typed, KmAverages.applied(typed, avg))
        assertEquals(KmBase(), KmAverages.applied(KmBase(), null))
    }
}
