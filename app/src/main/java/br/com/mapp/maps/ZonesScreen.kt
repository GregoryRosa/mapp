@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.pin.ZonePin
import br.com.mapp.core.pin.ZonePins
import br.com.mapp.finance.BackTopBar
import br.com.mapp.ui.MappColors
import br.com.mapp.ui.Routes
import br.com.mapp.ui.SupportTab
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Tela **Zonas**, em duas abas:
 * - **Minhas zonas**: o editor de sempre — desenhe o polígono no mapa e salve (R4).
 * - **Mapa de calor**: zonas que o app desenha sozinho a partir do histórico de ofertas
 *   ([HeatMapTab]), com botões de métrica e filtros.
 */
@Composable
fun ZonesScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SupportTab(Routes.ZONES, if (tab == 0) "Minhas zonas" else "Mapa de calor")
    Scaffold(topBar = { BackTopBar("Zonas", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = MappColors.Teal) {
                ZONE_TABS.forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        selectedContentColor = MappColors.Teal,
                        unselectedContentColor = MappColors.TextDim,
                    ) {
                        Icon(t.second, contentDescription = null, modifier = Modifier.padding(top = 10.dp).size(22.dp))
                        Text(
                            t.first,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (tab == 0) MyZonesTab() else HeatMapTab()
            }
        }
    }
}

private val ZONE_TABS = listOf(
    "Minhas zonas" to Icons.Filled.Place,
    "Mapa de calor" to Icons.Filled.LocalFireDepartment,
)

/** Aba Minhas zonas: toque no mapa para marcar os cantos e salve a área (R4). */
@Composable
private fun MyZonesTab() {
    val ctx = LocalContext.current
    val repos = MappApp.repos
    val scope = rememberCoroutineScope()

    val zonesFlow = remember { repos.zones.all() }
    val zones by zonesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val drawing = remember { mutableStateOf(false) }
    val vertices = remember { mutableStateListOf<GeoPoint>() }
    var current by remember { mutableStateOf<GeoPoint?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var zoneToDelete by remember { mutableStateOf<Zone?>(null) }
    var pinToDelete by remember { mutableStateOf<ZonePin?>(null) }
    /** Aba da lista de baixo: 0 = Zonas criadas, 1 = Pins para criar. */
    var listTab by rememberSaveable { mutableIntStateOf(0) }

    // Pins marcados na rua pelo 📍 do menu circular do "M".
    val pins by remember { PinStore.pins(ctx) }.collectAsStateWithLifecycle()
    var selectedPin by remember { mutableStateOf<ZonePin?>(null) }
    /** Pin que está virando zona (apagado quando a zona é salva). */
    var fromPin by remember { mutableStateOf<ZonePin?>(null) }
    /** Polígono pronto da "zona rápida" (sem desenhar). */
    var quickPolygon by remember { mutableStateOf<List<GeoPoint>?>(null) }

    val mapView = remember {
        MapViews.create(ctx, interactive = true).apply {
            // O ciclo de vida é controlado abaixo (onDetach no onDispose).
            setDestroyMode(false)
        }
    }
    // Fica sempre por cima: só consome toques no modo desenho; fora dele, deixa passar.
    val tapOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                if (!drawing.value || p == null) return false
                vertices.add(p.toCore())
                return true
            }

            override fun longPressHelper(p: OsmGeoPoint?): Boolean = false
        })
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(mapView) {
        onDispose { mapView.onDetach() }
    }

    LaunchedEffect(Unit) {
        val here = LocationProvider.get(ctx).current()
        current = here
        if (here != null) {
            MapViews.centerOn(mapView, here)
        } else {
            val saved = zonesFlow.first()
            MapViews.frame(mapView, saved.flatMap { it.polygon })
        }
    }

    fun resetDrawing() {
        drawing.value = false
        vertices.clear()
        fromPin = null
        quickPolygon = null
    }

    Column(Modifier.fillMaxSize()) {
        // clipToBounds: o MapView (osmdroid) desenha tiles além dos próprios limites e vazava por
        // baixo da faixa de botões (fundo transparente), deixando os botões ilegíveis sobre o mapa.
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    renderEditor(map, zones, pins, vertices.toList(), current, tapOverlay) { selectedPin = it }
                },
            )
            if (drawing.value) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                ) {
                    Text(
                        "Toque no mapa para marcar os cantos da zona (${vertices.size} ponto(s))",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        val here = LocationProvider.get(ctx).current()
                        if (here != null) {
                            current = here
                            MapViews.centerOn(mapView, here, zoom = mapView.zoomLevelDouble.coerceAtLeast(MapViews.CITY_ZOOM))
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = "Minha posição")
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MappColors.Navy)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (drawing.value) {
                val canUndo = vertices.isNotEmpty()
                OutlinedButton(
                    onClick = { if (vertices.isNotEmpty()) vertices.removeAt(vertices.lastIndex) },
                    enabled = canUndo,
                    colors = editorOutlinedColors(),
                    border = editorBorder(canUndo),
                ) { Text("Desfazer") }
                OutlinedButton(
                    onClick = { resetDrawing() },
                    colors = editorOutlinedColors(),
                    border = editorBorder(true),
                ) { Text("Cancelar") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { showSaveDialog = true },
                    enabled = vertices.size >= 3,
                    colors = editorPrimaryColors(),
                ) { Text("Salvar") }
            } else {
                Button(onClick = { resetDrawing(); drawing.value = true }, colors = editorPrimaryColors()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Desenhar zona")
                }
            }
        }

        // Lista em duas abas com altura FIXA de 3 linhas: o mapa fica com o resto da tela e não
        // encolhe conforme as zonas e os pins vão sendo criados (as linhas a mais rolam).
        HorizontalDivider()
        TabRow(selectedTabIndex = listTab, containerColor = Color.Transparent, contentColor = MappColors.Teal) {
            listOf("Zonas criadas (${zones.size})", "Pins para criar (${pins.size})").forEachIndexed { i, label ->
                Tab(
                    selected = listTab == i,
                    onClick = { listTab = i },
                    selectedContentColor = MappColors.Teal,
                    unselectedContentColor = MappColors.TextDim,
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        // Altura de uma linha medida na própria lista (acompanha o tamanho da fonte do celular);
        // antes da 1ª medida, uma estimativa pela escala da fonte.
        val density = LocalDensity.current
        var rowPx by remember { mutableIntStateOf(0) }
        val rowHeight = if (rowPx > 0) {
            with(density) { rowPx.toDp() }
        } else {
            maxOf(MIN_ROW_HEIGHT, (44f * density.fontScale + 16f).dp)
        }
        val measureRow = Modifier.onSizeChanged { if (it.height > rowPx) rowPx = it.height }
        Box(Modifier.fillMaxWidth().height(rowHeight * VISIBLE_ROWS)) {
            if (listTab == 0) {
                if (zones.isEmpty()) {
                    EmptyListText("Nenhuma zona salva. Desenhe áreas perigosas ou de alta demanda para receber alertas.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(zones, key = { it.id }) { zone ->
                            ListItem(
                                modifier = measureRow.clickable { MapViews.frame(mapView, zone.polygon) },
                                leadingContent = { ColorDot(zone.colorArgb.toInt() or 0xFF000000.toInt()) },
                                headlineContent = { OneLine(zone.name) },
                                supportingContent = {
                                    OneLine(zone.type.label + if (zone.alertEnabled) " · alerta ligado" else " · sem alerta")
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { MapViews.frame(mapView, zone.polygon) }) {
                                            Icon(Icons.Filled.Place, contentDescription = "Ver no mapa")
                                        }
                                        IconButton(onClick = { zoneToDelete = zone }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Excluir")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                if (pins.isEmpty()) {
                    EmptyListText("Nenhum pin. Toque no 📍 do menu do ícone \"M\" para marcar o local atual e desenhar a zona depois.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(pins, key = { "pin${it.id}" }) { pin ->
                            ListItem(
                                // Desenhando outra zona, o toque no pin não abre o diálogo (perderia o desenho).
                                modifier = measureRow.clickable(enabled = !drawing.value) {
                                    MapViews.centerOn(mapView, pin.point, zoom = PIN_ZOOM)
                                    selectedPin = pin
                                },
                                leadingContent = { ColorDot(pinColor(pin)) },
                                headlineContent = { OneLine(pin.type.label) },
                                supportingContent = { OneLine("${pinTime(pin)} · toque para criar") },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { MapViews.centerOn(mapView, pin.point, zoom = PIN_ZOOM) }) {
                                            Icon(Icons.Filled.Place, contentDescription = "Ver no mapa")
                                        }
                                        IconButton(onClick = { pinToDelete = pin }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Apagar pin")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        val pin = fromPin
        SaveZoneDialog(
            initialName = pin?.let { "${it.type.label} ${pinTime(it)}" } ?: "",
            initialType = pin?.type ?: ZoneType.DANGER,
            onDismiss = {
                showSaveDialog = false
                if (quickPolygon != null) resetDrawing()
            },
            onConfirm = { name, type, alert ->
                val polygon = quickPolygon ?: vertices.toList()
                showSaveDialog = false
                resetDrawing()
                listTab = 0
                scope.launch {
                    repos.zones.save(Zone(name = name, type = type, polygon = polygon, alertEnabled = alert))
                    pin?.let { PinStore.remove(ctx, it.id) }
                }
            },
        )
    }

    selectedPin?.let { pin ->
        AlertDialog(
            onDismissRequest = { selectedPin = null },
            title = { Text("📍 ${pin.type.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Marcado em ${pinTime(pin)}.")
                    Button(
                        onClick = {
                            selectedPin = null
                            resetDrawing()
                            fromPin = pin
                            drawing.value = true
                            MapViews.centerOn(mapView, pin.point, zoom = PIN_ZOOM)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Desenhar zona em volta") }
                    OutlinedButton(
                        onClick = {
                            selectedPin = null
                            resetDrawing()
                            fromPin = pin
                            quickPolygon = ZonePins.circle(pin.point)
                            showSaveDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Zona rápida (raio ${ZonePins.DEFAULT_RADIUS_M.toInt()} m)") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedPin = null
                    PinStore.remove(ctx, pin.id)
                }) { Text("Apagar pin") }
            },
            dismissButton = { TextButton(onClick = { selectedPin = null }) { Text("Fechar") } },
        )
    }

    pinToDelete?.let { pin ->
        AlertDialog(
            onDismissRequest = { pinToDelete = null },
            title = { Text("Apagar pin?") },
            text = { Text("O pin \"${pin.type.label}\" marcado em ${pinTime(pin)} será removido.") },
            confirmButton = {
                TextButton(onClick = {
                    pinToDelete = null
                    PinStore.remove(ctx, pin.id)
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { pinToDelete = null }) { Text("Cancelar") } },
        )
    }

    zoneToDelete?.let { zone ->
        AlertDialog(
            onDismissRequest = { zoneToDelete = null },
            title = { Text("Excluir zona?") },
            text = { Text("A zona \"${zone.name}\" será removida.") },
            confirmButton = {
                TextButton(onClick = {
                    zoneToDelete = null
                    scope.launch { repos.zones.delete(zone.id) }
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { zoneToDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
internal fun SaveZoneDialog(
    initialName: String = "",
    initialType: ZoneType = ZoneType.DANGER,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: ZoneType, alert: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var alert by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvar zona") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Tipo", style = MaterialTheme.typography.titleSmall)
                ZoneType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { type = t },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Box(
                            Modifier.size(12.dp).background(Color(t.defaultColorArgb.toInt() or 0xFF000000.toInt()), CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(t.label)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Avisar quando coleta/destino cair aqui", Modifier.weight(1f))
                    Switch(checked = alert, onCheckedChange = { alert = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), type, alert) },
                enabled = name.isNotBlank(),
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private const val PIN_ZOOM = 17.0

/** Linhas visíveis na lista de zonas/pins (as demais rolam). */
private const val VISIBLE_ROWS = 3

/** Altura mínima de uma linha de duas linhas de texto no Material 3. */
private val MIN_ROW_HEIGHT = 72.dp

@Composable
private fun OneLine(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun ColorDot(argb: Int) = Box(Modifier.size(18.dp).background(Color(argb), CircleShape))

@Composable
private fun EmptyListText(text: String) = Text(
    text,
    modifier = Modifier.padding(16.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = MappColors.TextDim,
)
private val PIN_TIME = SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR"))

private fun pinTime(pin: ZonePin): String = PIN_TIME.format(Date(pin.id))

/** Botão principal do editor (Salvar, Desenhar zona): verde-água do MApp; desligado fica cinza legível. */
@Composable
private fun editorPrimaryColors() = ButtonDefaults.buttonColors(
    containerColor = MappColors.Teal,
    contentColor = Color(0xFF00201D),
    disabledContainerColor = Color(0xFF1A3440),
    disabledContentColor = MappColors.TextDim,
)

/** Botões secundários do editor (Desfazer, Cancelar): texto claro sobre o fundo escuro. */
@Composable
private fun editorOutlinedColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = MappColors.Text,
    disabledContentColor = MappColors.TextDim.copy(alpha = 0.6f),
)

private fun editorBorder(enabled: Boolean) =
    BorderStroke(1.dp, if (enabled) MappColors.Aqua else MappColors.TextDim.copy(alpha = 0.35f))

private fun pinColor(pin: ZonePin): Int = pin.type.defaultColorArgb.toInt() or 0xFF000000.toInt()

/** Redesenha zonas, pins, prévia do desenho e posição atual; a camada de toques fica sempre por último. */
private fun renderEditor(
    map: MapView,
    zones: List<Zone>,
    pins: List<ZonePin>,
    draft: List<GeoPoint>,
    current: GeoPoint?,
    tapOverlay: MapEventsOverlay,
    onPinClick: (ZonePin) -> Unit,
) {
    MapViews.clear(map)
    MapViews.addZones(map, zones)
    pins.forEach { pin ->
        val label = when (pin.type) { ZoneType.DANGER -> "!"; ZoneType.HIGH_DEMAND -> "\$"; ZoneType.CUSTOM -> "★" }
        MapViews.addMarker(map, pin.point, label, pinColor(pin), "📍 ${pin.type.label}", sizeDp = 28f)
            .setOnMarkerClickListener { _, _ -> onPinClick(pin); true }
    }
    MapViews.addDraft(map, draft)
    current?.let { MapViews.addCurrentPosition(map, it) }
    map.overlays.add(tapOverlay)
    map.invalidate()
}
