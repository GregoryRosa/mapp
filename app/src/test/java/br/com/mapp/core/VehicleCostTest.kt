package br.com.mapp.core

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

    @Test fun consumo_zero_nao_divide_por_zero() {
        assertEquals(0.0, VehicleCostConfig(FuelType.DIESEL, 6.0, 0.0, 0.0, 0.0).energyCostPerKm, 1e-9)
    }
}
