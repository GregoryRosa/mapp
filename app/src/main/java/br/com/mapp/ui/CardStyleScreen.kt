@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.mapp.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.card.AddressItem
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.card.CardLine
import br.com.mapp.core.card.CardMetric
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.notify.NoticeItem
import br.com.mapp.core.notify.NoticeLayout
import br.com.mapp.core.notify.NoticeLine
import br.com.mapp.core.notify.RideNotice
import br.com.mapp.core.notify.RideNotices
import br.com.mapp.core.notify.RidePoints
import br.com.mapp.core.sound.GreatSound
import br.com.mapp.finance.BackTopBar
import br.com.mapp.overlay.CardHeaderView
import br.com.mapp.overlay.GreatSoundPlayer
import br.com.mapp.overlay.OverlayPrefs
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Espaço numa linha: [line] é um [CardLine] ou [AddressLine] (aba Card) ou um [NoticeLine] (aba Notificação). */
private data class Slot(val line: Any, val index: Int)

/** A linha de endereços do Card (itens [AddressItem], uma linha por ponto A, B, C…). */
private object AddressLine

/** Item sendo arrastado: [item] é um [CardMetric] ou um [NoticeItem]. */
private data class Drag(val item: Any, val label: String, val from: Slot?, val pos: Offset)

/** Arrastar com o dedo (segurar e arrastar): posição em coordenadas da raiz. */
private class DragController {
    var drag by mutableStateOf<Drag?>(null)
    var onDrop: (Drag) -> Unit = {}
    fun start(item: Any, label: String, from: Slot?, pos: Offset) { drag = Drag(item, label, from, pos) }
    fun move(delta: Offset) { drag = drag?.let { it.copy(pos = it.pos + delta) } }
    fun end() { drag?.let(onDrop); drag = null }
    fun cancel() { drag = null }
}

/**
 * Menu "Card" com dois submenus: **Card de corridas** (cabeçalho do cartão da oferta, igual ao
 * Rota PRO) e **Notificação** (o que aparece na notificação da corrida aceita). Nos dois: toque num
 * item disponível e depois num espaço; toque num item colocado para tirar; ou segure e arraste
 * (também entre espaços, para trocar de lugar). Salva na hora.
 */
@Composable
fun CardStyleScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { BackTopBar("Card", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Card de corridas") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Notificação") })
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (tab == 0) OfferCardTab() else NoticeTab()
            }
        }
    }
}

/** Submenu "Card de corridas": as linhas do cabeçalho do cartão da oferta (cima, baixo e endereços). */
@Composable
private fun OfferCardTab() {
    val ctx = LocalContext.current
    var layout by remember { mutableStateOf(OverlayPrefs.cardLayout(ctx)) }
    // Item escolhido para colocar: um CardMetric (linhas de cima/baixo) ou um AddressItem (linha de endereços).
    var selected by remember { mutableStateOf<Any?>(null) }
    var sample by remember { mutableIntStateOf(0) }
    val slotBounds = remember { mutableStateMapOf<Slot, Rect>() }
    val chipOrigins = remember { mutableStateMapOf<Any, Offset>() }
    var availableBounds by remember { mutableStateOf(Rect.Zero) }
    var addressAvailableBounds by remember { mutableStateOf(Rect.Zero) }
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
    // Cada item só entra na sua linha: métricas em cima/baixo, itens de endereço na linha de endereços.
    fun placeIn(item: Any, slot: Slot): CardLayout = when {
        item is CardMetric && slot.line is CardLine -> layout.place(item, slot.line, slot.index)
        item is AddressItem && slot.line == AddressLine -> layout.placeAddress(item, slot.index)
        else -> layout
    }
    ctl.onDrop = { d ->
        val target = slotAt(d.pos)
        val item = d.item
        when {
            target != null -> update(placeIn(item, target))
            d.from != null && item is CardMetric && availableBounds.contains(d.pos) -> update(layout.remove(item))
            d.from != null && item is AddressItem && addressAvailableBounds.contains(d.pos) -> update(layout.removeAddress(item))
        }
        selected = null
    }
    fun tapSlot(slot: Slot) {
        val sel = selected
        val occupant: Any? = if (slot.line == AddressLine) {
            layout.address.getOrNull(slot.index)
        } else {
            layout.line(slot.line as CardLine).getOrNull(slot.index)
        }
        if (sel != null) {
            update(placeIn(sel, slot))
            selected = null
        } else if (occupant is CardMetric) {
            update(layout.remove(occupant))
        } else if (occupant is AddressItem) {
            update(layout.removeAddress(occupant))
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInRoot() }) {
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
                            Text("Mostra Uber/99/inDrive no Card.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = layout.showPlatform, onCheckedChange = { update(layout.copy(showPlatform = it)) })
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tamanho das letras", fontWeight = FontWeight.SemiBold)
                        FontStepper("Linha de cima", layout.topFont) { update(layout.withFont(CardLine.TOP, it)) }
                        FontStepper("Números grandes", layout.bottomFont) { update(layout.withFont(CardLine.BOTTOM, it)) }
                        FontStepper("Linha de endereços", layout.addressFont) { update(layout.withAddressFont(it)) }
                        Text(
                            "Os números de baixo diminuem sozinhos se não couberem no Card.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                GreatSoundCard()
                StopSoundCard()
                StreetViewCard()

                CardLine.entries.forEach { line ->
                    SlotRow(
                        line = line,
                        title = line.label,
                        slots = line.slots,
                        items = layout.line(line),
                        labelOf = { (it as CardMetric).label },
                        ctl = ctl,
                        slotBounds = slotBounds,
                        hasSelection = selected is CardMetric,
                        slotAt = ::slotAt,
                        onTap = ::tapSlot,
                        boxHeight = 76.dp,
                    )
                }

                AvailableCard(
                    items = layout.available,
                    labelOf = { (it as CardMetric).label },
                    selected = selected,
                    onSelect = { selected = if (selected == it) null else it },
                    emptyText = "Todos os itens já estão no Card.",
                    ctl = ctl,
                    chipOrigins = chipOrigins,
                    onBounds = { availableBounds = it },
                )

                SlotRow(
                    line = AddressLine,
                    title = "Linha de endereços",
                    slots = CardLayout.ADDRESS_SLOTS,
                    items = layout.address,
                    labelOf = { (it as AddressItem).label },
                    ctl = ctl,
                    slotBounds = slotBounds,
                    hasSelection = selected is AddressItem,
                    slotAt = ::slotAt,
                    onTap = ::tapSlot,
                    boxHeight = 76.dp,
                )
                Text(
                    "Texto pequeno embaixo dos números, repetido para cada ponto: A = coleta (distância e tempo da busca), " +
                        "B, C… = paradas e a última letra = destino (distância e tempo da viagem, da coleta até lá). " +
                        "Vazia = não aparece no Card.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AvailableCard(
                    items = layout.availableAddress,
                    labelOf = { (it as AddressItem).label },
                    selected = selected,
                    onSelect = { selected = if (selected == it) null else it },
                    emptyText = "Os 3 itens já estão na linha de endereços.",
                    ctl = ctl,
                    chipOrigins = chipOrigins,
                    onBounds = { addressAvailableBounds = it },
                )

                TextButton(
                    onClick = { update(CardLayout.DEFAULT); selected = null },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("↺ Restaurar padrão") }
            }
        }
        DragGhost(ctl, rootOffset)
    }
}

/**
 * Linha de espaços numerados (aba Card e aba Notificação). Só o 1º espaço vazio recebe item (as
 * linhas não têm buracos). Toque: põe o item selecionado ou tira o que está lá; segure: arrasta.
 */
@Composable
private fun SlotRow(
    line: Any,
    title: String,
    slots: Int,
    items: List<Any>,
    labelOf: (Any) -> String,
    ctl: DragController,
    slotBounds: MutableMap<Slot, Rect>,
    hasSelection: Boolean,
    slotAt: (Offset) -> Slot?,
    onTap: (Slot) -> Unit,
    boxHeight: Dp,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text("${items.size}/$slots", style = MaterialTheme.typography.labelMedium)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(slots) { i ->
            val slot = Slot(line, i)
            val item = items.getOrNull(i)
            val usable = item != null || i == items.size
            val hovered = ctl.drag?.let { slotAt(it.pos) } == slot
            SlotBox(
                number = i + 1,
                label = item?.let(labelOf),
                usable = usable,
                highlighted = hovered || (hasSelection && usable),
                boxHeight = boxHeight,
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { slotBounds[slot] = it.boundsInRoot() }
                    .then(if (usable) Modifier.clickable { onTap(slot) } else Modifier)
                    .then(
                        if (item != null) {
                            Modifier.dragSource(ctl, item, labelOf(item), slot) { slotBounds[slot]?.topLeft ?: Offset.Zero }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** Itens que ainda não estão em nenhum espaço: toque para escolher, ou segure e arraste. */
@Composable
private fun AvailableCard(
    items: List<Any>,
    labelOf: (Any) -> String,
    selected: Any?,
    onSelect: (Any) -> Unit,
    emptyText: String,
    ctl: DragController,
    chipOrigins: MutableMap<Any, Offset>,
    onBounds: (Rect) -> Unit,
) {
    Card(Modifier.fillMaxWidth().onGloballyPositioned { onBounds(it.boundsInRoot()) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DISPONÍVEIS — TOQUE OU ARRASTE PARA UM ESPAÇO", style = MaterialTheme.typography.labelMedium)
            if (items.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    FilterChip(
                        selected = selected == item,
                        onClick = { onSelect(item) },
                        label = { Text(labelOf(item)) },
                        modifier = Modifier
                            .onGloballyPositioned { chipOrigins[item] = it.positionInRoot() }
                            .dragSource(ctl, item, labelOf(item), null) { chipOrigins[item] ?: Offset.Zero },
                    )
                }
            }
        }
    }
}

/** Item sendo arrastado, segue o dedo. */
@Composable
private fun DragGhost(ctl: DragController, rootOffset: Offset) {
    val d = ctl.drag ?: return
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
            d.label,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SlotBox(number: Int, label: String?, usable: Boolean, highlighted: Boolean, boxHeight: Dp, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(boxHeight)
            .clip(shape)
            .background(if (label != null) colors.surfaceVariant else colors.surface)
            .border(
                BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) colors.primary else colors.outlineVariant),
                shape,
            ),
    ) {
        if (label != null) {
            Text("$number", Modifier.padding(6.dp).align(Alignment.TopStart), style = MaterialTheme.typography.labelSmall)
            Text("✓", Modifier.padding(6.dp).align(Alignment.TopEnd), color = colors.primary, fontWeight = FontWeight.Bold)
            Text(
                label,
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

private fun Modifier.dragSource(ctl: DragController, item: Any, label: String, from: Slot?, origin: () -> Offset): Modifier =
    pointerInput(item, from) {
        detectDragGesturesAfterLongPress(
            onDragStart = { ctl.start(item, label, from, origin() + it) },
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
    "Curta" to RawOffer(
        Platform.UBER, 8.43, tripKm = 2.7, tripMin = 5, pickupKm = 1.6, pickupMin = 4, passengerRating = 4.92,
        pickupAddress = "Rua Augusta, 1200", dropoffAddress = "Av. Paulista, 900",
    ),
    "Média" to RawOffer(
        Platform.NINETY_NINE, 21.90, tripKm = 9.5, tripMin = 22, pickupKm = 2.1, pickupMin = 6, passengerRating = 4.85,
        pickupAddress = "Rua das Flores, 85", dropoffAddress = "Shopping Center Norte",
    ),
    // Com 1 parada (a Uber não mostra o endereço dela): A coleta, B parada, C destino.
    "Longa" to RawOffer(
        Platform.UBER, 29.20, tripKm = 22.0, tripMin = 45, pickupKm = 5.8, pickupMin = 15, passengerRating = 4.20,
        pickupAddress = "Av. Brasil, 4500", dropoffAddress = "Aeroporto de Congonhas", stopCount = 1,
    ),
)

/**
 * Som de corrida ótima: toca quando tudo no Card fica verde. Sons do MApp ou um som do celular
 * (seletor do sistema). Escolher já toca o som para ouvir.
 */
@Composable
private fun GreatSoundCard() {
    val ctx = LocalContext.current
    SoundCard(
        title = "Som de corrida ótima",
        hint = "Toca quando tudo no Card fica verde, antes da voz (toca mesmo com a voz desligada). Toque num som para ouvir.",
        initialSound = OverlayPrefs.greatSound(ctx),
        initialUri = OverlayPrefs.greatSoundUri(ctx),
        saveSound = { OverlayPrefs.setGreatSound(ctx, it) },
        saveUri = { OverlayPrefs.setGreatSoundUri(ctx, it) },
    )
}

/**
 * Aviso de corrida com parada no caminho ("1 parada" no Card da Uber): só avisa, quem recusa é você.
 * "Sem som" desliga o aviso. Com parada, ele toca no lugar do som de corrida ótima.
 */
@Composable
private fun StopSoundCard() {
    val ctx = LocalContext.current
    SoundCard(
        title = "Aviso de corrida com parada",
        hint = "Toca quando a oferta tem parada no caminho, antes da voz — para você decidir se recusa (o MApp nunca recusa sozinho). " +
            "Se a corrida também for ótima, toca este aviso no lugar do som de corrida ótima. \"Sem som\" desliga o aviso.",
        initialSound = OverlayPrefs.stopSound(ctx),
        initialUri = OverlayPrefs.stopSoundUri(ctx),
        saveSound = { OverlayPrefs.setStopSound(ctx, it) },
        saveUri = { OverlayPrefs.setStopSoundUri(ctx, it) },
    )
}

/** Escolha de um som (sons do MApp, som do celular ou sem som) com "Testar". Escolher já toca o som. */
@Composable
private fun SoundCard(
    title: String,
    hint: String,
    initialSound: GreatSound,
    initialUri: Uri?,
    saveSound: (GreatSound) -> Unit,
    saveUri: (Uri) -> Unit,
) {
    val ctx = LocalContext.current
    val player = remember { GreatSoundPlayer(ctx) }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    var sound by remember { mutableStateOf(initialSound) }
    var phoneUri by remember { mutableStateOf(initialUri) }
    val phoneTitle = remember(phoneUri) { GreatSoundPlayer.phoneSoundTitle(ctx, phoneUri) }

    fun choose(s: GreatSound, uri: Uri? = phoneUri) {
        sound = s
        saveSound(s)
        if (s == GreatSound.OFF) player.stop() else player.play(s, uri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            ?: return@rememberLauncherForActivityResult
        phoneUri = uri
        saveUri(uri)
        choose(GreatSound.PHONE, uri)
    }
    fun pickPhoneSound() {
        player.stop()
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, phoneUri)
        runCatching { picker.launch(intent) }
            .onFailure { Toast.makeText(ctx, "Este celular não abriu a lista de sons", Toast.LENGTH_LONG).show() }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GreatSound.entries.forEach { s ->
                    FilterChip(
                        selected = sound == s,
                        onClick = { if (s == GreatSound.PHONE && phoneUri == null) pickPhoneSound() else choose(s) },
                        label = { Text(s.label) },
                    )
                }
            }
            if (sound == GreatSound.PHONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔔 $phoneTitle", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = ::pickPhoneSound) { Text("Trocar") }
                }
            }
            OutlinedButton(enabled = sound != GreatSound.OFF, onClick = { player.play(sound, phoneUri) }) {
                Text("▶ Testar som")
            }
        }
    }
}

/** "Linha de cima   [−] 100% [+]" — passos de 10%. */
@Composable
private fun FontStepper(label: String, percent: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(
            onClick = { onChange(percent - CardLayout.FONT_STEP) },
            enabled = percent > CardLayout.FONT_MIN,
        ) { Text("−") }
        Text("$percent%", Modifier.width(56.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { onChange(percent + CardLayout.FONT_STEP) },
            enabled = percent < CardLayout.FONT_MAX,
        ) { Text("+") }
    }
}

// ------------------------------------------------------------------ submenu "Notificação"

/**
 * Submenu "Notificação": o que aparece na notificação da corrida aceita, por linha — título e
 * linha 2 (fechada e aberta) e 5 linhas da aberta (embaixo do mapa). Mesmo jeito do Card: toque ou
 * segure e arraste. Liga/desliga "Corrida aceita" no título e o mapa. Vale na próxima corrida aceita.
 */
@Composable
private fun NoticeTab() {
    val ctx = LocalContext.current
    var layout by remember { mutableStateOf(OverlayPrefs.noticeLayout(ctx)) }
    var selected by remember { mutableStateOf<NoticeItem?>(null) }
    var sample by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(true) }
    val slotBounds = remember { mutableStateMapOf<Slot, Rect>() }
    val chipOrigins = remember { mutableStateMapOf<Any, Offset>() }
    var availableBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val ctl = remember { DragController() }
    val acceptedAt = remember { System.currentTimeMillis() }

    val profile by remember { MappApp.repos.profiles.active() }.collectAsStateWithLifecycle(FilterProfile())
    val cost by remember { MappApp.repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    val chosen = NOTICE_SAMPLES[sample]
    val offer = remember(sample, profile, cost) {
        val zones = if (chosen.dangerAtDropoff) listOf(NOTICE_ZONE) else emptyList()
        OfferEvaluator.evaluate(chosen.raw, profile, cost, zones, NOTICE_PICKUP, NOTICE_DROPOFF)
            .copy(stops = chosen.stopPoints)
    }
    val notice = remember(offer, layout) {
        RideNotices.of(
            offer, chosen.raw.category, chosen.passenger, acceptedAt, ZoneId.systemDefault(),
            points = RidePoints(NOTICE_HERE, NOTICE_PICKUP, NOTICE_DROPOFF, chosen.stopPoints),
            layout = layout,
        )
    }

    fun update(l: NoticeLayout) {
        layout = l
        OverlayPrefs.setNoticeLayout(ctx, l)
    }
    fun slotAt(pos: Offset): Slot? = slotBounds.entries.firstOrNull { it.value.contains(pos) }?.key
    ctl.onDrop = { d ->
        val item = d.item as NoticeItem
        val target = slotAt(d.pos)
        when {
            target != null -> update(layout.place(item, target.line as NoticeLine, target.index))
            d.from != null && availableBounds.contains(d.pos) -> update(layout.remove(item))
        }
        selected = null
    }
    fun tapSlot(slot: Slot) {
        val line = slot.line as NoticeLine
        val sel = selected
        val occupant = layout.line(line).getOrNull(slot.index)
        if (sel != null) {
            update(layout.place(sel, line, slot.index))
            selected = null
        } else if (occupant != null) {
            update(layout.remove(occupant))
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            // Prévia fixa no topo: toque para abrir/fechar, como no celular.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NotificationPreview(notice, chosen.stopPoints.size, expanded, photos = layout.showPhotos) { expanded = !expanded }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !expanded, onClick = { expanded = false }, label = { Text("Fechada") })
                    FilterChip(selected = expanded, onClick = { expanded = true }, label = { Text("Aberta") })
                    NOTICE_SAMPLES.forEachIndexed { i, s ->
                        FilterChip(selected = sample == i, onClick = { sample = i }, label = { Text(s.label) })
                    }
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Toque num item disponível e depois num espaço. Toque num item colocado para tirar. " +
                        "Ou segure e arraste para mover ou trocar de lugar. Linha sem valor lido não aparece. " +
                        "Vale a partir da próxima corrida aceita.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow("\"Corrida aceita\" no título", "Texto fixo antes dos itens do título.", layout.headline) {
                            update(layout.copy(headline = it))
                        }
                        SwitchRow("Mapa na notificação aberta", "Você, coleta (A), destino (B) e zonas.", layout.showMap) {
                            update(layout.copy(showMap = it))
                        }
                        SwitchRow(
                            "Fotos da rua (A e B)",
                            if (OverlayPrefs.streetViewKey(ctx) != null) {
                                "Street View da coleta e do destino ao lado do mapa — as mesmas do Card, sem gastar de novo."
                            } else {
                                "Precisa da chave do Google: cole em Card de corridas → Fotos da rua."
                            },
                            layout.showPhotos,
                        ) { update(layout.copy(showPhotos = it)) }
                    }
                }

                Text("FECHADA E ABERTA", style = MaterialTheme.typography.labelMedium)
                NoticeLine.entries.forEach { line ->
                    if (line == NoticeLine.entries.first { it.expanded }) {
                        Text("SÓ ABERTA · EMBAIXO DO MAPA", style = MaterialTheme.typography.labelMedium)
                    }
                    SlotRow(
                        line = line,
                        title = line.label,
                        slots = line.slots,
                        items = layout.line(line),
                        labelOf = { (it as NoticeItem).label },
                        ctl = ctl,
                        slotBounds = slotBounds,
                        hasSelection = selected != null,
                        slotAt = ::slotAt,
                        onTap = ::tapSlot,
                        boxHeight = 60.dp,
                    )
                }

                AvailableCard(
                    items = layout.available,
                    labelOf = { (it as NoticeItem).label },
                    selected = selected,
                    onSelect = { selected = if (selected == it) null else it as NoticeItem },
                    emptyText = "Todos os itens já estão na notificação.",
                    ctl = ctl,
                    chipOrigins = chipOrigins,
                    onBounds = { availableBounds = it },
                )

                TextButton(
                    onClick = { update(NoticeLayout.DEFAULT); selected = null },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("↺ Restaurar padrão") }
            }
        }
        DragGhost(ctl, rootOffset)
    }
}

/**
 * Fotos da rua (Street View) da coleta, paradas e destino embaixo do mapa do Card. A chave do
 * Google é colada aqui e fica só no celular (o APK é público).
 */
@Composable
private fun StreetViewCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(OverlayPrefs.isStreetViewOn(ctx)) }
    var key by remember { mutableStateOf(OverlayPrefs.streetViewKey(ctx).orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    val used = remember {
        OverlayPrefs.streetViewBudget(ctx).usedIn(StreetViewBudget.monthOf(System.currentTimeMillis()))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                title = "Fotos da rua (Street View)",
                subtitle = "Foto da coleta (A) e do destino (B) — e da parada, se tiver endereço — embaixo do mapa. " +
                    "Toque numa foto para ampliar.",
                checked = on,
                onChange = { on = it; OverlayPrefs.setStreetViewOn(ctx, it) },
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); OverlayPrefs.setStreetViewKey(ctx, key) },
                label = { Text("Chave da API do Google") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Ver") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (key.isBlank()) {
                    "Cole a chave do Google Cloud (Street View Static API). Ela fica só neste celular."
                } else {
                    "Fotos este mês: ${"%,d".format(Locale("pt", "BR"), used)} de " +
                        "${"%,d".format(Locale("pt", "BR"), StreetView.LIMIT_PER_MONTH)}. " +
                        "O Google cobra a partir de 10.000 no mês; o MApp para antes."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Como a notificação fica no celular (fechada ou aberta), com um mapa desenhado de exemplo. */
@Composable
private fun NotificationPreview(n: RideNotice, stops: Int, expanded: Boolean, photos: Boolean, onToggle: () -> Unit) {
    val dim = Color(0xFFB0B8BC)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF22272B),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗺 MApp · agora", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = dim)
                Text(if (expanded) "▴" else "▾", color = dim)
            }
            Text(
                n.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (n.summary.isNotEmpty()) {
                Text(
                    n.summary,
                    color = Color(0xFFE0E0E0),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                if (n.showMap || photos) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (n.showMap) MapSketch(stops, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)))
                        if (photos) {
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PhotoSketch("A", Color(0xFF2E7D32), Modifier.weight(1f))
                                PhotoSketch(('A' + stops + 1).toString(), Color(0xFFC62828), Modifier.weight(1f))
                            }
                        }
                    }
                }
                n.details.forEach {
                    Text(
                        it,
                        color = Color(0xFFD0D0D0),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Lugar da foto da rua na prévia (a de verdade vem do Street View), com a letra do mapa. */
@Composable
private fun PhotoSketch(letter: String, color: Color, modifier: Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A4046))) {
        Box(
            Modifier.padding(4.dp).size(20.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) { Text(letter, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) }
        Text(
            "Foto da rua",
            Modifier.align(Alignment.Center),
            color = Color(0xFFB0B8BC),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Mapa de exemplo da prévia (o de verdade é o do OpenStreetMap): você → A tracejado, A → paradas →
 * destino com seta de sentido em cada trecho, letras A, B, C… como na notificação.
 */
@Composable
private fun MapSketch(stops: Int, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFFF2EFE9))) {
        val w = size.width
        val h = size.height
        val street = Color.White
        val streetW = 6.dp.toPx()
        listOf(0.2f, 0.55f, 0.85f).forEach { y -> drawLine(street, Offset(0f, h * y), Offset(w, h * (y + 0.08f)), streetW) }
        listOf(0.28f, 0.62f).forEach { x -> drawLine(street, Offset(w * x, 0f), Offset(w * (x - 0.04f), h), streetW) }
        val here = Offset(w * 0.12f, h * 0.74f)
        val a = Offset(w * 0.3f, h * 0.3f)
        val end = Offset(w * 0.86f, h * 0.66f)
        // Paradas espalhadas entre A e o destino, um pouco acima da reta.
        val middle = (1..stops).map { i ->
            val f = i / (stops + 1f)
            Offset(a.x + (end.x - a.x) * f, a.y + (end.y - a.y) * f - h * 0.22f)
        }
        val route = listOf(a) + middle + end
        val blue = Color(0xFF1E88E5)
        val dark = Color(0xFF37474F)
        val lineW = 3.dp.toPx()

        fun arrow(from: Offset, to: Offset, color: Color) {
            val d = to - from
            val len = d.getDistance()
            val size = 9.dp.toPx()
            if (len < 26.dp.toPx() + size * 2) return
            val u = d / len
            val n = Offset(-u.y, u.x)
            val tip = from + d / 2f + u * (size / 2)
            val base = tip - u * size
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(base.x + n.x * size * 0.6f, base.y + n.y * size * 0.6f)
                lineTo(base.x - n.x * size * 0.6f, base.y - n.y * size * 0.6f)
                close()
            }
            drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
            drawPath(path, color)
        }

        drawLine(
            blue, here, a, strokeWidth = lineW, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
        arrow(here, a, blue)
        route.zipWithNext { p, q ->
            drawLine(dark, p, q, strokeWidth = lineW, cap = StrokeCap.Round)
            arrow(p, q, dark)
        }

        val ring = 2.5.dp.toPx()
        val letter = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 12.dp.toPx()
        }
        drawCircle(Color.White, 8.dp.toPx(), here)
        drawCircle(blue, 8.dp.toPx() - ring, here)
        route.forEachIndexed { i, c ->
            val color = when (i) {
                0 -> Color(0xFF2E7D32)
                route.lastIndex -> Color(0xFFC62828)
                else -> Color(0xFFEF6C00)
            }
            val r = 11.dp.toPx()
            drawCircle(Color.White, r, c)
            drawCircle(color, r - ring, c)
            drawContext.canvas.nativeCanvas.drawText(
                ('A' + i).toString(), c.x, c.y - (letter.ascent() + letter.descent()) / 2f, letter,
            )
        }
    }
}

/** Corrida de exemplo da prévia da notificação ([stopPoints] = paradas no caminho já localizadas). */
private class NoticeSample(
    val label: String,
    val raw: RawOffer,
    val passenger: String?,
    val dangerAtDropoff: Boolean,
    val stopPoints: List<GeoPoint> = emptyList(),
)

private val NOTICE_HERE = GeoPoint(-23.5540, -46.6620)
private val NOTICE_PICKUP = GeoPoint(-23.5614, -46.6559)
private val NOTICE_DROPOFF = GeoPoint(-23.5874, -46.6576)
private val NOTICE_ZONE = Zone(
    name = "Centro",
    type = ZoneType.DANGER,
    polygon = listOf(
        GeoPoint(-23.592, -46.662), GeoPoint(-23.592, -46.653),
        GeoPoint(-23.583, -46.653), GeoPoint(-23.583, -46.662),
    ),
)

private val NOTICE_SAMPLES = listOf(
    NoticeSample(
        "Curta",
        RawOffer(
            Platform.UBER, 8.43, tripKm = 2.7, tripMin = 5, pickupKm = 1.6, pickupMin = 4, passengerRating = 4.92,
            pickupAddress = "Rua Augusta, 900", dropoffAddress = "Av. Paulista, 1000", category = "UberX",
        ),
        passenger = "caio",
        dangerAtDropoff = false,
    ),
    NoticeSample(
        "Longa",
        RawOffer(
            Platform.UBER, 29.20, tripKm = 22.0, tripMin = 45, pickupKm = 5.8, pickupMin = 15, passengerRating = 4.20,
            pickupAddress = "Rua Vergueiro, 2000", dropoffAddress = "Aeroporto de Congonhas", category = "Comfort",
        ),
        passenger = "ana",
        dangerAtDropoff = false,
    ),
    NoticeSample(
        "Com zona",
        RawOffer(
            Platform.NINETY_NINE, 21.90, tripKm = 9.5, tripMin = 22, pickupKm = 2.1, pickupMin = 6, passengerRating = 4.85,
            pickupAddress = "Rua da Consolação, 300", dropoffAddress = "Rua 25 de Março, 50", category = "Pop",
        ),
        passenger = null,
        dangerAtDropoff = true,
    ),
    NoticeSample(
        "Com parada",
        RawOffer(
            Platform.UBER, 32.10, tripKm = 16.2, tripMin = 35, pickupKm = 1.8, pickupMin = 5, passengerRating = 4.90,
            pickupAddress = "Rua Augusta, 1500", dropoffAddress = "Av. Paulista, 900", category = "UberX",
            stopAddresses = listOf("Rua Oscar Freire, 200"), stopCount = 1,
        ),
        passenger = "bia",
        dangerAtDropoff = false,
        stopPoints = listOf(GeoPoint(-23.5640, -46.6700)),
    ),
)
