@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftSummary
import br.com.mapp.finance.BackTopBar
import br.com.mapp.finance.EmptyText
import br.com.mapp.overlay.ShiftTracker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
private val DAY = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", PT_BR)
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", PT_BR)

/** Jornadas encerradas: totais da semana/mês e uma linha por jornada (toque para detalhes). */
@Composable
fun ShiftHistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val shifts by remember { ShiftTracker.history(ctx) }.collectAsStateWithLifecycle()
    val cost by remember { MappApp.repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    var toDelete by remember { mutableStateOf<Shift?>(null) }

    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now(zone)
    val rows = shifts.map { it to ShiftMath.summary(it, cost.costPerKm, now) }
    fun dateOf(s: Shift): LocalDate = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
    val week = rows.filter { !dateOf(it.first).isBefore(today.minusDays(6)) }.map { it.second }
    val month = rows.filter { dateOf(it.first).let { d -> d.year == today.year && d.month == today.month } }
        .map { it.second }

    Scaffold(topBar = { BackTopBar("Histórico de jornadas", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rows.isEmpty()) {
                item {
                    EmptyText(
                        "Nenhuma jornada encerrada ainda. Use ▶ no ícone \"M\" ao começar a rodar e encerre " +
                            "no fim do dia: cada jornada aparece aqui.",
                    )
                }
            } else {
                item { TotalCard("Últimos 7 dias", week) }
                item { TotalCard("Este mês", month) }
                item {
                    Text(
                        "${rows.size} jornada${if (rows.size == 1) "" else "s"} · toque para ver os detalhes",
                        style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun TotalCard(title: String, list: List<ShiftSummary>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "$title · ${list.size} jornada${if (list.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (list.isEmpty()) {
                Text("Sem jornadas no período.", style = MaterialTheme.typography.bodySmall)
            } else {
                ShiftMath.lines(ShiftMath.combine(list)).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun ShiftRow(shift: Shift, s: ShiftSummary, zone: ZoneId, onDelete: () -> Unit) {
    var expanded by rememberSaveable(shift.startedAt) { mutableStateOf(false) }
    val start = Instant.ofEpochMilli(shift.startedAt).atZone(zone)
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DAY.format(start).replaceFirstChar { it.uppercase(PT_BR) } + " · " + HOUR.format(start),
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                Text(ShiftMath.money(s.revenue), fontWeight = FontWeight.Bold)
            }
            Text(
                "${ShiftMath.duration(s.hours)} · ${s.trips} corrida${if (s.trips == 1) "" else "s"} · " +
                    "${ShiftMath.km(s.totalKm)} · lucro ${ShiftMath.money(s.profit)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                ShiftMath.lines(s).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onDelete) { Text("Apagar jornada") }
            }
        }
    }
}
