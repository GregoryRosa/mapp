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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.finance.Format
import br.com.mapp.finance.NetToggle
import br.com.mapp.finance.ReportMath
import br.com.mapp.finance.rememberNow
import br.com.mapp.finance.rememberPeriodTotals
import br.com.mapp.overlay.OverlayService
import br.com.mapp.overlay.ShiftTracker
import br.com.mapp.reader.OfferAccessibilityService
import br.com.mapp.reader.ReaderStatus

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
        containerColor = Color.Transparent,
        bottomBar = { BottomMenu(onNavigate) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Cabeçalho: logo + nome + versão + selo de status
            Row(verticalAlignment = Alignment.CenterVertically) {
                MappLogo(48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("MApp", style = MaterialTheme.typography.titleLarge, color = MappColors.Text)
                    Text("Copiloto · versão $version", style = MaterialTheme.typography.labelMedium, color = MappColors.Teal)
                }
                StatusPill(running)
            }

            if (updatedFrom != null) {
                GlassCard(glow = true) {
                    Text(
                        "✅ Atualização instalada: versão $version" +
                            if (updatedFrom.isNotEmpty()) " (antes era a $updatedFrom)" else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            // Cartão principal do copiloto
            GlassCard(glow = true, padding = PaddingValues(20.dp)) {
                Text(
                    if (running) "Copiloto ativo" else "Pronto para rodar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MappColors.Text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (running) "O ícone flutuante fica por cima da Uber/99/inDrive: toque nele para ajustes rápidos."
                    else "Analise cada oferta, controle a jornada e o lucro — tudo num só app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MappColors.TextDim,
                )
                if (!p.accessibility) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ Sem o leitor de Acessibilidade ativo o MApp não vê as chamadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MappColors.Warn,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (running) {
                    OutlinedButton(
                        onClick = {
                            OverlayService.stop(ctx)
                            Toast.makeText(ctx, "Copiloto parado", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MappColors.Border),
                    ) { Text("Parar copiloto", color = MappColors.Text) }
                } else {
                    GradientButton("Iniciar copiloto") {
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
                    }
                }
            }

            ShiftCard(running)

            // Permissões: completas = uma linha; faltando = lista com "Ativar"
            val allOk = p.location && p.overlay && p.accessibility && p.battery
            GlassCard {
                Text("Permissões", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (allOk) {
                    Text("✅ Tudo ativado", style = MaterialTheme.typography.bodyMedium, color = MappColors.Profit)
                } else {
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

            GlassCard {
                Text("Leitor de corridas", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                ReaderStatus.lines(reader, running).forEachIndexed { i, line ->
                    if (i == 0) {
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = MappColors.Text)
                    } else {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MappColors.TextDim)
                    }
                }
                if (!reader.connected) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Se a Acessibilidade aparecer bloqueada (\"Configuração restrita\"): Configurações → Apps → MApp → " +
                            "menu ⋮ → \"Permitir configurações restritas\" e ative de novo o leitor MApp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MappColors.Warn,
                    )
                }
            }

            Text(
                "O MApp só lê as ofertas. Ele nunca aceita ou recusa corridas por você.",
                style = MaterialTheme.typography.bodySmall,
                color = MappColors.TextDim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val color = if (running) MappColors.Teal else MappColors.TextDim
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (running) "Ativo" else "Parado", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private data class MenuItem(val label: String, val icon: ImageVector, val route: String)

private val MENU = listOf(
    MenuItem("Ganhos", Icons.Outlined.AccountBalanceWallet, Routes.FINANCE),
    MenuItem("Relatórios", Icons.Outlined.BarChart, Routes.REPORTS),
    MenuItem("Jornadas", Icons.Outlined.Timer, Routes.SHIFTS),
    MenuItem("Ofertas", Icons.Outlined.History, Routes.HISTORY),
    MenuItem("Perfis", Icons.Outlined.Tune, Routes.PROFILES),
    MenuItem("Card", Icons.Outlined.Style, Routes.CARD_STYLE),
    MenuItem("Veículo", Icons.Outlined.DirectionsCar, Routes.VEHICLE),
    MenuItem("Zonas", Icons.Outlined.Map, Routes.ZONES),
)

/** Menu de baixo em vidro escuro, duas linhas de 4 botões. */
@Composable
private fun BottomMenu(onNavigate: (String) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(shape).background(Color(0xE60B161D))
            .border(1.dp, Brush.linearGradient(listOf(MappColors.Border, Color(0x33B59CFF))), shape)
            .padding(vertical = 6.dp, horizontal = 6.dp),
    ) {
        MENU.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .clickable { onNavigate(item.route) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(Color(0x335EE6D8), Color(0x22B59CFF)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, contentDescription = null, tint = MappColors.Teal, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, style = MaterialTheme.typography.labelSmall, color = MappColors.TextDim, maxLines = 1)
                    }
                }
            }
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

/**
 * Jornada e números do dia (o da jornada aberta, mesmo que tenha começado ontem; senão hoje). Mesma
 * conta de Ganhos e gastos e do histórico ([rememberPeriodTotals]). Valor bruto sempre à vista; o botão
 * ao lado do título troca para o líquido (tirando o custo do carro e os gastos lançados).
 */
@Composable
private fun ShiftCard(copilotRunning: Boolean) {
    val ctx = LocalContext.current
    val current by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    val now = rememberNow()
    val cur = current
    val period = ReportMath.workDay(cur?.startedAt, now)
    val t = rememberPeriodTotals(period, now)
    val today = ReportMath.dayOf(now)
    var net by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                when {
                    cur == null -> "Hoje"
                    cur.paused -> "⏸ Jornada pausada"
                    else -> "● Jornada rodando"
                } + if (period != today) " · ${Format.day(period.from)}" else "",
            )
            Spacer(Modifier.width(10.dp))
            NetToggle(
                net, onToggle = { net = !net },
                modifier = Modifier.padding(top = 4.dp),
                activeColor = MappColors.Profit, idleColor = MappColors.TextDim,
            )
        }
        if (t.isEmpty) {
            GlassCard {
                Text(
                    "Ao começar a rodar, toque no ícone flutuante e em ▶ Iniciar jornada. O MApp soma os km pelo GPS e " +
                        "conta as corridas sozinho.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Tempo", ShiftMath.duration(t.hours), Modifier.weight(1f))
                StatTile("Corridas", t.rides.toString(), Modifier.weight(1f))
                if (net) {
                    StatTile(
                        "Líquido", ShiftMath.money(t.net), Modifier.weight(1f),
                        valueColor = if (t.net >= 0) MappColors.Profit else MappColors.Loss,
                    )
                } else {
                    StatTile("Ganho", ShiftMath.money(t.gross), Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Rodados", ShiftMath.km(t.km), Modifier.weight(1f))
                val perKm = if (net) t.netPerKm else t.perKm
                val perHour = if (net) t.netPerHour else t.perHour
                StatTile(if (net) "R$/km líq." else "R$/km", perKm?.let { ShiftMath.num(it, 2) } ?: "—", Modifier.weight(1f))
                StatTile(if (net) "R$/h líq." else "R$/hora", perHour?.let { ShiftMath.num(it, 2) } ?: "—", Modifier.weight(1f))
            }
            GlassCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                val dim = MaterialTheme.typography.bodySmall
                Text("Ganho ${ShiftMath.money(t.gross)} · Custos ${ShiftMath.money(t.cost)}", style = MaterialTheme.typography.bodyMedium)
                if (net) {
                    Text(
                        "Carro ${ShiftMath.money(t.carCost)} (${ShiftMath.km(t.km)} × R$ " +
                            "${t.carCostPerKm?.let { ShiftMath.num(it, 2) } ?: "—"}/km)" +
                            if (t.expenses > 0) " + gastos lançados ${ShiftMath.money(t.expenses)}" else "",
                        style = dim, color = MappColors.TextDim,
                    )
                }
                if (t.pendingRides > 0) {
                    Text("🚕 ${t.pendingRides} em andamento · soma no ganho ao finalizar", style = dim, color = MappColors.Teal)
                }
                Text(
                    "Com passageiro ${ShiftMath.km(t.kmWithTrip)} · sem ${ShiftMath.km(t.kmWithoutTrip)}",
                    style = dim, color = MappColors.TextDim,
                )
                Text("Média por corrida ${t.avgTripKm?.let { ShiftMath.km(it) } ?: "—"}", style = dim, color = MappColors.TextDim)
                if (t.categories.isNotEmpty()) {
                    Text(
                        t.categories.joinToString(" · ") { "${it.label} ${ShiftMath.num(it.percent, 0)}%" },
                        style = dim, color = MappColors.Lilac,
                    )
                }
                if (cur != null && !cur.paused && !copilotRunning) {
                    Text("⚠️ Copiloto parado: os km não estão sendo contados.", style = dim, color = MappColors.Warn)
                }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (ok) "✅ " else "⚠️ ") + label, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MappColors.Text else MappColors.Warn,
        )
        if (!ok) TextButton(onClick = onFix) { Text("Ativar", color = MappColors.Teal) }
    }
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
