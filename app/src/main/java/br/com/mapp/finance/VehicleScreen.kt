@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
    var fuel by remember(initial) { mutableStateOf(Format.input(initial.fuelPricePerLiter)) }
    var kmPerLiter by remember(initial) { mutableStateOf(Format.input(initial.kmPerLiter)) }
    var maintenance by remember(initial) { mutableStateOf(Format.input(initial.maintenancePerKm)) }
    var depreciation by remember(initial) { mutableStateOf(Format.input(initial.depreciationPerKm)) }

    val fuelV = Format.parse(fuel)
    val kmlV = Format.parse(kmPerLiter)
    val maintV = Format.parse(maintenance)
    val depV = Format.parse(depreciation)
    val config = if (fuelV != null && kmlV != null && kmlV > 0 && maintV != null && depV != null &&
        fuelV >= 0 && maintV >= 0 && depV >= 0
    ) {
        VehicleCostConfig(fuelV, kmlV, maintV, depV)
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
        NumberField("Preço do combustível", fuel, { fuel = it }, suffix = "R$/l")
        NumberField("Consumo", kmPerLiter, { kmPerLiter = it }, suffix = "km/l")
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
                        "Combustível ${Format.perKm(config.fuelPricePerLiter / config.kmPerLiter)} + " +
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
