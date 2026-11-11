package br.com.mapp.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/** Editor de zonas: toque no mapa para adicionar vértices e salve como zona (R4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneEditorScreen(onBack: () -> Unit) {
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zonas no mapa") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                    update = { map ->
                        renderEditor(map, zones, vertices.toList(), current, tapOverlay)
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
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (drawing.value) {
                    OutlinedButton(
                        onClick = { if (vertices.isNotEmpty()) vertices.removeAt(vertices.lastIndex) },
                        enabled = vertices.isNotEmpty(),
                    ) { Text("Desfazer") }
                    OutlinedButton(onClick = { resetDrawing() }) { Text("Cancelar") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showSaveDialog = true }, enabled = vertices.size >= 3) { Text("Salvar") }
                } else {
                    Button(onClick = { vertices.clear(); drawing.value = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Desenhar zona")
                    }
                }
            }

            HorizontalDivider()
            if (zones.isEmpty()) {
                Text(
                    "Nenhuma zona salva. Desenhe áreas perigosas ou de alta demanda para receber alertas.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(zones, key = { it.id }) { zone ->
                        ListItem(
                            modifier = Modifier.clickable { MapViews.frame(mapView, zone.polygon) },
                            leadingContent = {
                                Box(
                                    Modifier.size(18.dp).background(
                                        Color(zone.colorArgb.toInt() or 0xFF000000.toInt()),
                                        CircleShape,
                                    ),
                                )
                            },
                            headlineContent = { Text(zone.name) },
                            supportingContent = {
                                Text(zone.type.label + if (zone.alertEnabled) " · alerta ligado" else " · sem alerta")
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
        }
    }

    if (showSaveDialog) {
        SaveZoneDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, type, alert ->
                val polygon = vertices.toList()
                showSaveDialog = false
                resetDrawing()
                scope.launch {
                    repos.zones.save(Zone(name = name, type = type, polygon = polygon, alertEnabled = alert))
                }
            },
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
private fun SaveZoneDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: ZoneType, alert: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ZoneType.DANGER) }
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

/** Redesenha zonas, prévia do desenho e posição atual; a camada de toques fica sempre por último. */
private fun renderEditor(
    map: MapView,
    zones: List<Zone>,
    draft: List<GeoPoint>,
    current: GeoPoint?,
    tapOverlay: MapEventsOverlay,
) {
    MapViews.clear(map)
    MapViews.addZones(map, zones)
    MapViews.addDraft(map, draft)
    current?.let { MapViews.addCurrentPosition(map, it) }
    map.overlays.add(tapOverlay)
    map.invalidate()
}
