package br.com.mapp.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.overlay.OverlayService
import br.com.mapp.overlay.ShiftTracker
import kotlinx.coroutines.delay
import br.com.mapp.reader.OfferAccessibilityService
import br.com.mapp.reader.ReaderStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    // Reavalia permissões sempre que o usuário volta das Configurações.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ }
    }
    val runtimePerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val p = remember(refresh) { Perms.check(ctx) }
    val running by OverlayService.running.collectAsState()
    val reader by ReaderStatus.state.collectAsState()
    // Versão instalada, sempre à vista: muda a cada APK novo.
    val version = remember { ReaderStatus.versionOf(ctx) }
    val updatedFrom = remember { versionChange(ctx, version) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("MApp – Copiloto")
                    Text("Versão $version", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            })
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (updatedFrom != null) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "✅ Atualização instalada: versão $version" +
                            if (updatedFrom.isNotEmpty()) " (antes era a $updatedFrom)" else "",
                        modifier = Modifier.padding(16.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Permissões", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    PermRow("Localização", p.location) {
                        val list = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        runtimePerms.launch(list.toTypedArray())
                    }
                    PermRow("Sobrepor outros apps", p.overlay) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                    PermRow("Acessibilidade (leitor)", p.accessibility) {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermRow("Sem otimização de bateria", p.battery) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (running) "🟢 Copiloto ATIVO" else "⚪ Copiloto parado",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (running) "O ícone \"M\" flutuante fica por cima da Uber/99/inDrive: toque nele para ajustes rápidos."
                        else "Toque em Iniciar para analisar as ofertas.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    if (!p.accessibility) {
                        Text(
                            "⚠️ Sem o leitor de Acessibilidade ativo o MApp não vê as chamadas.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (running) {
                        OutlinedButton(onClick = {
                            OverlayService.stop(ctx)
                            Toast.makeText(ctx, "Copiloto parado", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Parar copiloto") }
                    } else {
                        Button(onClick = {
                            val missing = buildList {
                                if (!p.overlay) add("Sobrepor outros apps")
                                if (!p.location) add("Localização")
                            }
                            if (missing.isNotEmpty()) {
                                Toast.makeText(ctx, "Ative antes: " + missing.joinToString(" e "), Toast.LENGTH_LONG).show()
                            } else {
                                OverlayService.start(ctx)
                                if (!p.accessibility) {
                                    Toast.makeText(ctx, "Copiloto iniciado, mas ative a Acessibilidade (leitor)", Toast.LENGTH_LONG).show()
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Iniciar copiloto") }
                    }
                }
            }

            ShiftCard(running)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Leitor de corridas", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    ReaderStatus.lines(reader, running).forEachIndexed { i, line ->
                        if (i == 0) {
                            Text(line)
                        } else {
                            Text(line, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!reader.connected) {
                        Text(
                            "Se a Acessibilidade aparecer bloqueada (\"Configuração restrita\"): Configurações → Apps → MApp → " +
                                "menu ⋮ → \"Permitir configurações restritas\" e ative de novo o leitor MApp.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Text("Menu", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            MenuButton("Ganhos e gastos") { onNavigate(Routes.FINANCE) }
            MenuButton("Relatórios") { onNavigate(Routes.REPORTS) }
            MenuButton("Histórico de jornadas") { onNavigate(Routes.SHIFTS) }
            MenuButton("Histórico de ofertas") { onNavigate(Routes.HISTORY) }
            MenuButton("Perfis de filtro") { onNavigate(Routes.PROFILES) }
            MenuButton("Estilo do cartão") { onNavigate(Routes.CARD_STYLE) }
            MenuButton("Custos do veículo") { onNavigate(Routes.VEHICLE) }
            MenuButton("Zonas no mapa") { onNavigate(Routes.ZONES) }

            Text(
                "O MApp só lê as ofertas. Ele nunca aceita ou recusa corridas por você.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Compara a versão instalada com a vista na última abertura (e guarda a atual). Devolve a versão
 * anterior se mudou ("" na primeira vez), ou null se é a mesma — aí não há aviso de atualização.
 */
private fun versionChange(ctx: Context, current: String): String? {
    val prefs = ctx.getSharedPreferences("mapp_ui", Context.MODE_PRIVATE)
    val last = prefs.getString("last_version", null)
    if (last == current) return null
    prefs.edit().putString("last_version", current).apply()
    return last ?: ""
}

/** Jornada atual (ou a última encerrada): mesmos números do painel do ícone "M". */
@Composable
private fun ShiftCard(copilotRunning: Boolean) {
    val ctx = LocalContext.current
    val current by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    val last by remember { ShiftTracker.last(ctx) }.collectAsStateWithLifecycle()
    val cost by remember { MappApp.repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val shift = current ?: last
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    current == null -> "Jornada"
                    current!!.paused -> "⏸ Jornada pausada"
                    else -> "● Jornada rodando"
                },
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            if (shift == null) {
                Text(
                    "Ao começar a rodar, toque no ícone \"M\" e em ▶ Iniciar jornada. O MApp soma os km pelo GPS e " +
                        "as corridas que você marcar com +1.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            } else {
                if (current == null) {
                    Text("Última jornada encerrada", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                ShiftMath.lines(ShiftMath.summary(shift, cost.costPerKm, now)).forEach { Text(it) }
                if (current != null && !current!!.paused && !copilotRunning) {
                    Text(
                        "⚠️ Copiloto parado: os km não estão sendo contados.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✅ " else "⚠️ ") + label, Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Ativar") }
    }
}

@Composable
private fun MenuButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

data class Perms(val location: Boolean, val overlay: Boolean, val accessibility: Boolean, val battery: Boolean) {
    companion object {
        fun check(ctx: Context): Perms {
            val loc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val overlay = Settings.canDrawOverlays(ctx)
            // O sistema pode gravar a forma longa ou curta ("pkg/.Classe"): compara como ComponentName.
            val expected = ComponentName(ctx, OfferAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            return Perms(loc, overlay, enabled, pm.isIgnoringBatteryOptimizations(ctx.packageName))
        }
    }
}
