@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.repo.OfferRecord
import kotlinx.coroutines.launch

private const val HISTORY_LIMIT = 200

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val repos = MappApp.repos
    // 0 = Aceitas hoje (padrão: o que importa na jornada), 1 = Todas as ofertas.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val today = remember { ReportMath.today() }
    val todayOffers by remember { repos.offers.between(today.from, today.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val offers by remember { repos.offers.recent(HISTORY_LIMIT) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val accepted = remember(todayOffers) { ReportMath.accepted(todayOffers) }
    val summary = remember(todayOffers) { ReportMath.summarizeAccepted(todayOffers) }
    val scope = rememberCoroutineScope()
    val toggle: (OfferRecord) -> Unit = { r ->
        scope.launch { repos.offers.setAcceptedById(r.id, if (r.accepted == true) false else true) }
    }

    Scaffold(topBar = { BackTopBar("Histórico de ofertas", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Aceitas hoje (${accepted.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Todas") })
            }
            val list = if (tab == 0) accepted else offers
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == 0) {
                    if (accepted.isEmpty()) {
                        item {
                            EmptyText(
                                "Nenhuma corrida aceita hoje. Elas entram aqui sozinhas quando o MApp vê a tela " +
                                    "\"Encontro com…\" ou quando você toca +1. Também dá para marcar na aba Todas.",
                            )
                        }
                    } else {
                        item { AcceptedSummaryCard(summary) }
                    }
                } else if (offers.isEmpty()) {
                    item { EmptyText("Nenhuma oferta registrada ainda. Inicie o copiloto e as ofertas lidas aparecerão aqui.") }
                } else {
                    item {
                        Text(
                            "Últimas ${offers.size} ofertas (máx. $HISTORY_LIMIT). Toque para ver os motivos e marcar se aceitou.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(list, key = { it.id }) { r -> OfferCard(r, onToggleAccepted = { toggle(r) }) }
            }
        }
    }
}

@Composable
private fun AcceptedSummaryCard(s: AcceptedSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${s.count} ${if (s.count == 1) "corrida" else "corridas"} · ${Format.brl(s.total)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Lucro ${Format.brl(s.profit)} · ${Format.perKm(s.avgPerKm)} · ${Format.perHour(s.avgPerHour)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun OfferCard(record: OfferRecord, onToggleAccepted: () -> Unit) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val e = record.evaluated
    val raw = e.raw
    val m = e.metrics
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalDot(e.signal)
                Text(raw.category ?: raw.platform.label, style = MaterialTheme.typography.labelLarge)
                if (record.accepted == true) {
                    Text("✓ Aceita", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(Format.brl(raw.price), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(Format.dayTime(raw.capturedAt), style = MaterialTheme.typography.bodySmall)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Recolher" else "Expandir",
                )
            }
            Text(
                "${Format.perKm(m.perKm)} · ${Format.perHour(m.perHour)} · ${Format.km(m.totalKm)} · ${Format.minutes(m.totalMin)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${raw.pickupAddress ?: "Coleta ?"} → ${raw.dropoffAddress ?: "Destino ?"}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Text(
                    "Busca: ${Format.km(raw.pickupKm)} / ${Format.minutes(raw.pickupMin)} · " +
                        "Viagem: ${Format.km(raw.tripKm)} / ${Format.minutes(raw.tripMin)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Lucro líquido: ${Format.brl(m.netProfit)} (${Format.perKm(m.netPerKm)})" +
                        (raw.passengerRating?.let { " · Nota ${"%.2f".format(Format.PT_BR, it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (e.reasons.isEmpty()) {
                    Text("Nenhum alerta: dentro do perfil.", style = MaterialTheme.typography.bodySmall)
                } else {
                    e.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = onToggleAccepted) {
                    Text(if (record.accepted == true) "Desmarcar aceita" else "Marcar como aceita")
                }
            }
        }
    }
}
