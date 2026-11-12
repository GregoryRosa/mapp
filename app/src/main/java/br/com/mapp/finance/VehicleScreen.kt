@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.mapp.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.model.VehicleCostConfig
import kotlinx.coroutines.launch

@Composable
fun VehicleScreen(onBack: () -> Unit) {
    val repos = MappApp.repos
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val saved by remember { repos.vehicle.config() }
        .collectAsStateWithLifecycle<VehicleCostConfig?>(initialValue = null)

    Scaffold(
        topBar = { BackTopBar("Custos do veículo", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val current = saved
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            VehicleForm(
                initial = current,
                modifier = Modifier.padding(pad),
                onSave = { cfg ->
                    scope.launch {
                        repos.vehicle.save(cfg)
                        snackbar.showSnackbar("Custos salvos: ${Format.perKm(cfg.costPerKm)}")
                    }
                },
            )
        }
    }
}

@Composable
private fun VehicleForm(initial: VehicleCostConfig, modifier: Modifier, onSave: (VehicleCostConfig) -> Unit) {
    var fuelType by remember(initial) { mutableStateOf(initial.fuelType) }
    var fuel by remember(initial) { mutableStateOf(Format.input(initial.fuelPricePerUnit)) }
    var kmPerLiter by remember(initial) { mutableStateOf(Format.input(initial.kmPerUnit)) }

    // Trocar o tipo preenche preço/consumo típicos; voltar ao tipo salvo restaura os valores salvos.
    fun selectType(type: FuelType) {
        if (type == fuelType) return
        fuelType = type
        val price = if (type == initial.fuelType) initial.fuelPricePerUnit else type.suggestedPrice
        val km = if (type == initial.fuelType) initial.kmPerUnit else type.suggestedKmPerUnit
        fuel = Format.input(price)
        kmPerLiter = Format.input(km)
    }
    var maintenance by remember(initial) { mutableStateOf(Format.input(initial.maintenancePerKm)) }
    var depreciation by remember(initial) { mutableStateOf(Format.input(initial.depreciationPerKm)) }

    val fuelV = Format.parse(fuel)
    val kmlV = Format.parse(kmPerLiter)
    val maintV = Format.parse(maintenance)
    val depV = Format.parse(depreciation)
    val config = if (fuelV != null && kmlV != null && kmlV > 0 && maintV != null && depV != null &&
        fuelV >= 0 && maintV >= 0 && depV >= 0
    ) {
        VehicleCostConfig(fuelType, fuelV, kmlV, maintV, depV)
    } else {
        null
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Combustível", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelType.entries.forEach { t ->
                FilterChip(selected = fuelType == t, onClick = { selectType(t) }, label = { Text(t.label) })
            }
        }
        if (fuelType.isElectric) {
            NumberField("Preço da energia (recarga)", fuel, { fuel = it }, suffix = fuelType.priceSuffix)
            NumberField("Consumo", kmPerLiter, { kmPerLiter = it }, suffix = fuelType.consumptionSuffix)
            Text(
                "Use o preço do kWh da sua recarga (conta de luz em casa ou eletroposto) e a eficiência do carro em km/kWh.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            NumberField("Preço do ${fuelType.label.lowercase()}", fuel, { fuel = it }, suffix = fuelType.priceSuffix)
            NumberField("Consumo", kmPerLiter, { kmPerLiter = it }, suffix = fuelType.consumptionSuffix)
        }
        NumberField("Manutenção por km", maintenance, { maintenance = it }, suffix = "R$/km")
        NumberField("Depreciação por km", depreciation, { depreciation = it }, suffix = "R$/km")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Custo por km", style = MaterialTheme.typography.labelLarge)
                if (config == null) {
                    Text("Preencha todos os campos (consumo maior que zero).", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        Format.perKm(config.costPerKm),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${if (fuelType.isElectric) "Energia" else fuelType.label} ${Format.perKm(config.energyCostPerKm)} + " +
                            "manutenção ${Format.perKm(config.maintenancePerKm)} + " +
                            "depreciação ${Format.perKm(config.depreciationPerKm)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Ex.: corrida de 10 km custa ${Format.brl(config.costPerKm * 10)}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Button(
            enabled = config != null && config != initial,
            onClick = { config?.let(onSave) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Salvar") }
        Text(
            "O custo por km é usado para calcular o lucro líquido de cada oferta.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
