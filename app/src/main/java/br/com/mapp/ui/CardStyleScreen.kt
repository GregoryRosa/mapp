@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.mapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.card.CardLine
import br.com.mapp.core.card.CardMetric
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.finance.BackTopBar
import br.com.mapp.overlay.CardHeaderView
import br.com.mapp.overlay.OverlayPrefs
import kotlin.math.roundToInt

private data class Slot(val line: CardLine, val index: Int)

private data class Drag(val metric: CardMetric, val from: Slot?, val pos: Offset)

/** Arrastar com o dedo (segurar e arrastar): posição em coordenadas da raiz. */
private class DragController {
    var drag by mutableStateOf<Drag?>(null)
    var onDrop: (Drag) -> Unit = {}
    fun start(metric: CardMetric, from: Slot?, pos: Offset) { drag = Drag(metric, from, pos) }
    fun move(delta: Offset) { drag = drag?.let { it.copy(pos = it.pos + delta) } }
    fun end() { drag?.let(onDrop); drag = null }
    fun cancel() { drag = null }
}

/**
 * "Estilo do cartão" (igual ao Rota PRO): escolhe o que aparece nas duas linhas do cabeçalho do
 * cartão. Toque num item disponível e depois num espaço; toque num item colocado para tirar;
 * ou segure e arraste (também entre espaços, para trocar de lugar). Salva na hora.
 */
@Composable
fun CardStyleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var layout by remember { mutableStateOf(OverlayPrefs.cardLayout(ctx)) }
    var selected by remember { mutableStateOf<CardMetric?>(null) }
    var sample by remember { mutableIntStateOf(0) }
    val slotBounds = remember { mutableStateMapOf<Slot, Rect>() }
    val chipOrigins = remember { mutableStateMapOf<CardMetric, Offset>() }
    var availableBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val ctl = remember { DragController() }

    val profile by remember { MappApp.repos.profiles.active() }.collectAsStateWithLifecycle(FilterProfile())
    val cost by remember { MappApp.repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    val offer = remember(sample, profile, cost) { OfferEvaluator.evaluate(SAMPLES[sample].second, profile, cost) }

    fun update(l: CardLayout) {
        layout = l
        OverlayPrefs.setCardLayout(ctx, l)
    }
    fun slotAt(pos: Offset): Slot? = slotBounds.entries.firstOrNull { it.value.contains(pos) }?.key
    ctl.onDrop = { d ->
        val target = slotAt(d.pos)
        when {
            target != null -> update(layout.place(d.metric, target.line, target.index))
            d.from != null && availableBounds.contains(d.pos) -> update(layout.remove(d.metric))
        }
        selected = null
    }
    fun tapSlot(slot: Slot) {
        val sel = selected
        val occupant = layout.line(slot.line).getOrNull(slot.index)
        if (sel != null) {
            update(layout.place(sel, slot.line, slot.index))
            selected = null
        } else if (occupant != null) {
            update(layout.remove(occupant))
        }
    }

    Scaffold(topBar = { BackTopBar("Estilo do cartão", onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).onGloballyPositioned { rootOffset = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize()) {
                // Prévia fixa no topo, com a largura real do cartão.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val header = remember { CardHeaderView(ctx) }
                    AndroidView(
                        factory = { header.view },
                        modifier = Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)),
                        update = { header.bind(offer, profile, layout) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SAMPLES.forEachIndexed { i, (label, _) ->
                            FilterChip(selected = sample == i, onClick = { sample = i }, label = { Text(label) })
                        }
                    }
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Toque num item disponível e depois num espaço. Toque num item colocado para tirar. " +
                            "Ou segure e arraste para mover ou trocar de lugar. As cores das barrinhas seguem o perfil ativo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Nome do app", fontWeight = FontWeight.SemiBold)
                                Text("Mostra Uber/99/inDrive no cartão.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = layout.showPlatform, onCheckedChange = { update(layout.copy(showPlatform = it)) })
                        }
                    }

                    CardLine.entries.forEach { line ->
                        val items = layout.line(line)
                        Row(Modifier.fillMaxWidth()) {
                            Text(line.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${items.size}/${line.slots}", style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(line.slots) { i ->
                                val slot = Slot(line, i)
                                val metric = items.getOrNull(i)
                                // Só o 1º espaço vazio recebe item (as linhas não têm buracos).
                                val usable = metric != null || i == items.size
                                val hovered = ctl.drag?.let { slotAt(it.pos) } == slot
                                SlotBox(
                                    number = i + 1,
                                    metric = metric,
                                    usable = usable,
                                    highlighted = hovered || (selected != null && usable),
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { slotBounds[slot] = it.boundsInRoot() }
                                        .then(if (usable) Modifier.clickable { tapSlot(slot) } else Modifier)
                                        .then(
                                            if (metric != null) {
                                                Modifier.dragSource(ctl, metric, slot) { slotBounds[slot]?.topLeft ?: Offset.Zero }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                )
                            }
                        }
                    }

                    Card(Modifier.fillMaxWidth().onGloballyPositioned { availableBounds = it.boundsInRoot() }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DISPONÍVEIS — TOQUE OU ARRASTE PARA UM ESPAÇO", style = MaterialTheme.typography.labelMedium)
                            val available = layout.available
                            if (available.isEmpty()) {
                                Text("Todos os itens já estão no cartão.", style = MaterialTheme.typography.bodySmall)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                available.forEach { metric ->
                                    FilterChip(
                                        selected = selected == metric,
                                        onClick = { selected = if (selected == metric) null else metric },
                                        label = { Text(metric.label) },
                                        modifier = Modifier
                                            .onGloballyPositioned { chipOrigins[metric] = it.positionInRoot() }
                                            .dragSource(ctl, metric, null) { chipOrigins[metric] ?: Offset.Zero },
                                    )
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = { update(CardLayout.DEFAULT); selected = null },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("↺ Restaurar padrão") }
                }
            }

            // Item sendo arrastado, segue o dedo.
            ctl.drag?.let { d ->
                val density = LocalDensity.current
                val dx = with(density) { 48.dp.toPx() }
                val dy = with(density) { 24.dp.toPx() }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .zIndex(10f)
                        .offset {
                            IntOffset((d.pos.x - rootOffset.x - dx).roundToInt(), (d.pos.y - rootOffset.y - dy).roundToInt())
                        },
                ) {
                    Text(
                        d.metric.label,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotBox(number: Int, metric: CardMetric?, usable: Boolean, highlighted: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(76.dp)
            .clip(shape)
            .background(if (metric != null) colors.surfaceVariant else colors.surface)
            .border(
                BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) colors.primary else colors.outlineVariant),
                shape,
            ),
    ) {
        if (metric != null) {
            Text("$number", Modifier.padding(6.dp).align(Alignment.TopStart), style = MaterialTheme.typography.labelSmall)
            Text("✓", Modifier.padding(6.dp).align(Alignment.TopEnd), color = colors.primary, fontWeight = FontWeight.Bold)
            Text(
                metric.label,
                Modifier.align(Alignment.Center).padding(horizontal = 4.dp, vertical = 2.dp).offset(y = 6.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        } else if (usable) {
            Text("+", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall, color = colors.outline)
        }
    }
}

private fun Modifier.dragSource(ctl: DragController, metric: CardMetric, from: Slot?, origin: () -> Offset): Modifier =
    pointerInput(metric, from) {
        detectDragGesturesAfterLongPress(
            onDragStart = { ctl.start(metric, from, origin() + it) },
            onDrag = { change, amount ->
                change.consume()
                ctl.move(amount)
            },
            onDragEnd = { ctl.end() },
            onDragCancel = { ctl.cancel() },
        )
    }

/** Ofertas de exemplo para a prévia (avaliadas com o perfil e os custos atuais). */
private val SAMPLES = listOf(
    "Curta" to RawOffer(Platform.UBER, 8.43, tripKm = 2.7, tripMin = 5, pickupKm = 1.6, pickupMin = 4, passengerRating = 4.92),
    "Média" to RawOffer(Platform.NINETY_NINE, 21.90, tripKm = 9.5, tripMin = 22, pickupKm = 2.1, pickupMin = 6, passengerRating = 4.85),
    "Longa" to RawOffer(Platform.UBER, 29.20, tripKm = 22.0, tripMin = 45, pickupKm = 5.8, pickupMin = 15, passengerRating = 4.20),
)
