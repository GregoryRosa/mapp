@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import br.com.mapp.ui.Routes
import br.com.mapp.ui.SupportTab
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.core.shift.ShiftTrip
import br.com.mapp.core.trip.TripCapture
import br.com.mapp.core.trip.TripDetails
import br.com.mapp.overlay.ShiftTracker
import kotlinx.coroutines.launch

/** Ofertas que a aba "Todas" mostra de cara; o botão "Carregar mais" vai somando outro tanto. */
private const val HISTORY_PAGE = 100

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val repos = MappApp.repos
    val context = LocalContext.current
    // 0 = Aceitas hoje (padrão: o que importa na jornada), 1 = Todas as ofertas.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SupportTab(Routes.HISTORY, if (tab == 0) "Aceitas hoje" else "Todas")
    val now = rememberNow()
    val openShift by remember { ShiftTracker.current(context) }.collectAsStateWithLifecycle()
    // "Aceitas hoje" = dia de trabalho (jornada aberta depois da meia-noite continua no dia em que começou).
    val today = ReportMath.workDay(openShift?.startedAt, now)
    val todayOffers by remember(today) { repos.offers.between(today.from, today.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // A lista começa curta (a tela fica leve) e cresce quando o motorista pede mais.
    var limit by rememberSaveable { mutableIntStateOf(HISTORY_PAGE) }
    val offers by remember(limit) { repos.offers.recent(limit) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val accepted = remember(todayOffers) { ReportMath.accepted(todayOffers) }
    // Resumo do dia com a mesma conta da tela inicial e de Ganhos e gastos (km do GPS, tempo da jornada).
    val summary = rememberPeriodTotals(today, now)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toggle: (OfferRecord) -> Unit = { r ->
        val accept = r.accepted != true
        val raw = r.evaluated.raw
        scope.launch {
            repos.offers.setAcceptedById(r.id, accept)
            // Também coloca/tira a corrida da jornada (ícone "M", resumo do dia, histórico de jornadas).
            val inShift = ShiftTracker.setTrip(ctx, ShiftTrip.from(raw, raw.capturedAt), raw.capturedAt, accept)
            val msg = when {
                !inShift -> "Nenhuma jornada nesse horário: a corrida conta só no histórico e nos relatórios"
                accept -> "Corrida somada à jornada"
                else -> "Corrida tirada da jornada"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    // Arrastar para a esquerda (aba Todas): apaga o registro; corrida aceita sai também da jornada.
    val delete: (OfferRecord) -> Unit = { r ->
        val raw = r.evaluated.raw
        val wasAccepted = r.accepted == true
        scope.launch {
            repos.offers.delete(r.id)
            if (wasAccepted) ShiftTracker.setTrip(ctx, ShiftTrip.from(raw, raw.capturedAt), raw.capturedAt, false)
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = if (wasAccepted) "Oferta excluída e tirada da jornada" else "Oferta excluída",
                actionLabel = "Desfazer",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val id = repos.offers.record(r.evaluated)
                repos.offers.setAcceptedById(id, r.accepted)
                if (wasAccepted) ShiftTracker.setTrip(ctx, ShiftTrip.from(raw, raw.capturedAt), raw.capturedAt, true)
            }
        }
    }

    // Inserir viagem que o MApp não pegou: lida da tela "Detalhes da viagem" da Uber ou digitada.
    var manual by remember { mutableStateOf<Pair<TripDetails, Boolean>?>(null) }
    val armedUntil by TripCapture.armedUntil.collectAsStateWithLifecycle()
    val captured by TripCapture.result.collectAsStateWithLifecycle()
    LaunchedEffect(captured) {
        captured?.let {
            manual = it to true
            TripCapture.consume()
        }
    }
    val pull: () -> Unit = {
        manual = null
        Toast.makeText(ctx, pullFromUber(ctx).message(), Toast.LENGTH_LONG).show()
    }
    manual?.let { (initial, fromUber) ->
        ManualTripDialog(
            initial = initial,
            fromUber = fromUber,
            onDismiss = { manual = null },
            onPull = pull,
            onSave = { d, at, match ->
                manual = null
                scope.launch {
                    val msg = try {
                        saveManualTrip(ctx, d, at, match)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        "Não deu para inserir a viagem: ${e.message}"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    Scaffold(
        topBar = { BackTopBar("Histórico de ofertas", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = pull, modifier = Modifier.weight(1f)) { Text("📲 Inserir viagem da Uber") }
                OutlinedButton(onClick = { manual = TripDetails() to false }) { Text("✎ Digitar") }
            }
            if (armedUntil > now) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "📲 Esperando a viagem na Uber: abra Ganhos → a viagem (tela Detalhes da viagem).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { TripCapture.cancel() }) { Text("Cancelar") }
                    }
                }
            }
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
                            "Últimas ${offers.size} ofertas. Toque para ver os motivos e marcar se aceitou. " +
                                "Arraste para a esquerda para excluir. (O mapa de calor usa todas as ofertas " +
                                "gravadas, não só estas.)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(list, key = { it.id }) { r ->
                    if (tab == 1) {
                        SwipeToDelete(onDelete = { delete(r) }, modifier = Modifier.animateItem()) {
                            OfferCard(r, onToggleAccepted = { toggle(r) })
                        }
                    } else {
                        OfferCard(r, onToggleAccepted = { toggle(r) })
                    }
                }
                // Veio o tanto que foi pedido: pode haver mais guardado.
                if (tab == 1 && offers.size >= limit) {
                    item {
                        OutlinedButton(
                            onClick = { limit += HISTORY_PAGE },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Carregar mais $HISTORY_PAGE") }
                    }
                }
            }
        }
    }
}

/** Card que sai arrastando para a esquerda (40% da largura); fundo vermelho com "Excluir". */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.4f })
    val currentOnDelete by rememberUpdatedState(onDelete)
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) currentOnDelete()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val swiping = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(if (swiping) MaterialTheme.colorScheme.error else Color.Transparent)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (swiping) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Excluir", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                    }
                }
            }
        },
    ) { content() }
}

@Composable
private fun AcceptedSummaryCard(t: PeriodTotals) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${t.rides} ${if (t.rides == 1) "corrida" else "corridas"} · ${Format.brl(t.ridesGross)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${Format.km(t.km)} rodados · ${Format.perKm(t.perKm)} · ${Format.perHour(t.perHour)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (t.manualIncome > 0) {
                Text("+ ${Format.brl(t.manualIncome)} em ganhos lançados", style = MaterialTheme.typography.bodySmall)
            }
            if (t.pendingRides > 0) {
                Text(
                    "🚕 ${t.pendingRides} em andamento · soma no ganho ao finalizar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
