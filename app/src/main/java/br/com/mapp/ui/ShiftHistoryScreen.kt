@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.mapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.shift.CategoryShare
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftSummary
import br.com.mapp.finance.BackTopBar
import br.com.mapp.finance.EmptyText
import br.com.mapp.finance.GreenSignal
import br.com.mapp.finance.RedSignal
import br.com.mapp.finance.ReportMath
import br.com.mapp.finance.rememberNow
import br.com.mapp.overlay.ShiftTracker
import br.com.mapp.overlay.TripLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
private val DAY = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", PT_BR)
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", PT_BR)

/**
 * Jornadas: a do dia no topo (aberta ou encerrada hoje), totais de 7 dias e do mês e uma linha por
 * jornada encerrada (toque para detalhes). A jornada aberta entra nos totais.
 */
@Composable
fun ShiftHistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val shifts by remember { ShiftTracker.history(ctx) }.collectAsStateWithLifecycle()
    val openShift by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    val cost by remember { MappApp.repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    var toDelete by remember { mutableStateOf<Shift?>(null) }
    // Corridas sem tipo nas jornadas (antigas ou contadas antes do tipo ser lido) recebem o tipo do histórico.
    LaunchedEffect(Unit) { fillShiftCategories(ctx) }

    val zone = ZoneId.systemDefault()
    val now = rememberNow()
    val today = LocalDate.now(zone)
    val rows = shifts.map { it to ShiftMath.summary(it, cost.costPerKm, now, cost.fixedShare) }
    val openRow = openShift?.let { it to ShiftMath.summary(it, cost.costPerKm, now, cost.fixedShare) }
    val all = listOfNotNull(openRow) + rows
    fun dateOf(s: Shift): LocalDate = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
    // "Hoje" = dia de trabalho: jornada aberta que passou da meia-noite continua no dia em que começou.
    val workDay = ReportMath.workDay(openShift?.startedAt, now, zone)
    val todayList = all.filter { it.first.startedAt in workDay }.map { it.second }
    val week = all.filter { !dateOf(it.first).isBefore(today.minusDays(6)) }.map { it.second }
    val month = all.filter { dateOf(it.first).let { d -> d.year == today.year && d.month == today.month } }
        .map { it.second }

    Scaffold(
        topBar = {
            BackTopBar("Histórico de jornadas", onBack) {
                // Registro das corridas (ofertas, telas lidas e decisões do copiloto) para diagnóstico.
                TextButton(onClick = {
                    val log = TripLog.text(ctx)
                    ctx.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("MApp", log.ifBlank { "Registro vazio" }))
                    Toast.makeText(ctx, "Registro das corridas copiado: é só colar", Toast.LENGTH_LONG).show()
                }) { Text("Registro") }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TotalCard(
                    title = "Jornada do dia",
                    list = todayList,
                    status = openShift?.let { "● Em andamento desde ${HOUR.format(Instant.ofEpochMilli(it.startedAt).atZone(zone))}" },
                    empty = "Nenhuma jornada hoje. Toque ▶ no ícone \"M\" ao começar a rodar.",
                )
            }
            if (rows.isEmpty() && openRow == null) {
                item {
                    EmptyText(
                        "Nenhuma jornada encerrada ainda. Use ▶ no ícone \"M\" ao começar a rodar e encerre " +
                            "no fim do dia: cada jornada aparece aqui.",
                    )
                }
            } else {
                item { TotalCard("Últimos 7 dias", week) }
                item { TotalCard("Este mês", month) }
            }
            if (rows.isNotEmpty()) {
                item {
                    Text(
                        "${rows.size} jornada${if (rows.size == 1) "" else "s"} encerrada${if (rows.size == 1) "" else "s"} · " +
                            "toque para ver os detalhes",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(rows, key = { it.first.startedAt }) { (shift, summary) ->
                    ShiftRow(shift, summary, zone) { toDelete = shift }
                }
            }
        }
    }

    toDelete?.let { shift ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Apagar jornada?") },
            text = { Text("A jornada de ${DAY.format(Instant.ofEpochMilli(shift.startedAt).atZone(zone))} sai do histórico.") },
            confirmButton = {
                TextButton(onClick = {
                    ShiftTracker.deleteFromHistory(ctx, shift.startedAt)
                    toDelete = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * Completa o tipo das corridas sem tipo (jornada atual e encerradas, até [FILL_DAYS] dias) com as
 * ofertas gravadas no histórico. Roda fora da thread principal; falha = deixa como está.
 */
internal suspend fun fillShiftCategories(ctx: Context) {
    try {
        withContext(Dispatchers.Default) {
            val shifts = listOfNotNull(ShiftTracker.current(ctx).value) + ShiftTracker.history(ctx).value
            if (shifts.any { s -> s.trips.any { it.category.isNullOrBlank() } }) {
                val now = System.currentTimeMillis()
                val from = maxOf(shifts.minOf { it.startedAt } - 60 * 60_000L, now - FILL_DAYS * 86_400_000L)
                val offers = MappApp.repos.offers.between(from, now).first().map { it.evaluated.raw }
                ShiftTracker.fillCategories(ctx, offers)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("MApp/Jornadas", "Falha ao completar tipos das corridas", e)
    }
}

private const val FILL_DAYS = 62L

@Composable
private fun TotalCard(title: String, list: List<ShiftSummary>, status: String? = null, empty: String = "Sem jornadas no período.") {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (list.isNotEmpty()) {
                        Text(
                            " · ${list.size} jornada${if (list.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = GreenSignal) }
            }
            if (list.isEmpty()) {
                Text(empty, style = MaterialTheme.typography.bodySmall)
            } else {
                SummaryBlock(ShiftMath.combine(list))
            }
        }
    }
}

@Composable
private fun ShiftRow(shift: Shift, s: ShiftSummary, zone: ZoneId, onDelete: () -> Unit) {
    var expanded by rememberSaveable(shift.startedAt) { mutableStateOf(false) }
    val start = Instant.ofEpochMilli(shift.startedAt).atZone(zone)
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DAY.format(start).replaceFirstChar { it.uppercase(PT_BR) } + " · " + HOUR.format(start),
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                Text(ShiftMath.money(s.revenue), fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SummaryBlock(s)
                TextButton(onClick = onDelete) { Text("Apagar jornada") }
            } else {
                Text(
                    "${ShiftMath.duration(s.hours)} · ${s.trips} corrida${if (s.trips == 1) "" else "s"} · " +
                        "${ShiftMath.km(s.totalKm)} · lucro ${ShiftMath.money(s.profit)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Números da jornada em grade de 3 colunas + barras de km (com/sem passageiro) e tipos de corrida. */
@Composable
private fun SummaryBlock(s: ShiftSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Stat("Ganho", ShiftMath.money(s.revenue), Modifier.weight(1f))
            Stat("Custo", ShiftMath.money(s.cost), Modifier.weight(1f), RedSignal)
            Stat("Lucro", ShiftMath.money(s.profit), Modifier.weight(1f), if (s.profit < 0) RedSignal else GreenSignal)
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Tempo", ShiftMath.duration(s.hours), Modifier.weight(1f))
            Stat("Corridas", s.trips.toString(), Modifier.weight(1f))
            Stat("Km rodados", ShiftMath.km(s.totalKm), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("R$/km", s.perKm?.let { ShiftMath.num(it, 2) } ?: "—", Modifier.weight(1f))
            Stat("R$/h", s.perHour?.let { ShiftMath.num(it, 2) } ?: "—", Modifier.weight(1f))
            Stat("Média/corrida", s.avgTripKm?.let { ShiftMath.km(it) } ?: "—", Modifier.weight(1f))
        }
        if (s.pendingTrips > 0) {
            Text(
                "🚕 ${s.pendingTrips} em andamento · soma no lucro ao finalizar",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val kmSum = s.kmWithTrip + s.kmWithoutTrip
        if (kmSum > 0) {
            val pct = (s.kmWithTrip * 100 / kmSum).toInt()
            BarSection(
                title = "Km com e sem passageiro",
                parts = listOf(
                    Part("Com passageiro ${ShiftMath.km(s.kmWithTrip)} ($pct%)", s.kmWithTrip, WITH_TRIP),
                    Part("Sem ${ShiftMath.km(s.kmWithoutTrip)}", s.kmWithoutTrip, WITHOUT_TRIP),
                ),
            )
        }
        if (s.categories.isNotEmpty()) {
            val colors = categoryColors(s.categories.map { it.label })
            BarSection(
                title = "Tipos de corrida",
                parts = s.categories.map { c: CategoryShare ->
                    Part("${c.label} ${ShiftMath.num(c.percent, 0)}% (${c.count})", c.count.toDouble(), colors.getValue(c.label))
                },
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

internal class Part(val label: String, val weight: Double, val color: Color)

/** Barra dividida pelas partes (largura proporcional) com legenda colorida embaixo. */
@Composable
internal fun BarSection(title: String, parts: List<Part>) {
    val shown = parts.filter { it.weight > 0 }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shown.forEach { p -> Box(Modifier.weight(p.weight.toFloat()).fillMaxHeight().background(p.color)) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shown.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(p.color, CircleShape))
                    Text(p.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

internal val WITH_TRIP = Color(0xFF4DD0B8)
internal val WITHOUT_TRIP = Color(0xFF546E7A)

/** Cor fixa por tipo de corrida (a mesma em todas as jornadas); tipos novos pegam uma cor livre. */
private val CATEGORY_COLORS = mapOf(
    "UberX" to Color(0xFF4FC3F7),
    "Comfort" to Color(0xFF4DD0B8),
    "Black" to Color(0xFFB0BEC5),
    "Electric" to Color(0xFFAED581),
    "Prioridade" to Color(0xFFFFB74D),
    "Flash" to Color(0xFFFFF176),
    "Moto" to Color(0xFFE57373),
    "Juntos" to Color(0xFFBA68C8),
    "Bag" to Color(0xFFA1887F),
    "Pop" to Color(0xFFFFD54F),
    "Pop Expresso" to Color(0xFFFFA726),
    "Táxi" to Color(0xFFFF8A65),
)
private val EXTRA_COLORS = listOf(
    Color(0xFFB39DDB), Color(0xFFF06292), Color(0xFF4DD0E1), Color(0xFF7986CB), Color(0xFF81C784), Color(0xFFFF8A65),
)

internal fun categoryColors(labels: List<String>): Map<String, Color> {
    val out = LinkedHashMap<String, Color>()
    labels.forEach { l -> CATEGORY_COLORS[l]?.let { out[l] = it } }
    var next = 0
    labels.filter { it !in out }.forEach { l ->
        val free = EXTRA_COLORS.firstOrNull { it !in out.values }
        out[l] = free ?: EXTRA_COLORS[next++ % EXTRA_COLORS.size]
    }
    return out
}
