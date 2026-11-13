package br.com.mapp.core

import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.ExtraKmCostCodec
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.model.VehicleCostConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleCostTest {
    @Test fun eletrico_usa_preco_do_kwh_e_km_por_kwh() {
        val c = VehicleCostConfig(FuelType.ELECTRIC, fuelPricePerUnit = 0.90, kmPerUnit = 6.0, maintenancePerKm = 0.05, depreciationPerKm = 0.25)
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
}
