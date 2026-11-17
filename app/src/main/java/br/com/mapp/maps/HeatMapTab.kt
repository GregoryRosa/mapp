@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.geo.Geo
import br.com.mapp.core.heat.HeatFilter
import br.com.mapp.core.heat.HeatGrid
import br.com.mapp.core.heat.HeatHours
import br.com.mapp.core.heat.HeatMap
import br.com.mapp.core.heat.HeatMetric
import br.com.mapp.core.heat.HeatPalette
import br.com.mapp.core.heat.HeatPeriod
import br.com.mapp.core.heat.HeatPoints
import br.com.mapp.core.heat.HeatScale
import br.com.mapp.core.heat.HeatSet
import br.com.mapp.core.heat.HeatSpot
import br.com.mapp.core.heat.HeatZone
import br.com.mapp.core.heat.HeatZones
import br.com.mapp.core.heat.HexMath
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.Zone
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.finance.Format
import br.com.mapp.ui.MappColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

/**
 * Aba **Mapa de calor** da tela Zonas.
 *
 * Pega o histórico de ofertas do aparelho, joga cada uma no quadradinho da grade pela coordenada da
 * coleta (ou do destino), pinta o quadradinho pela métrica escolhida e junta os quentes em zonas que
 * o motorista pode salvar como zona de verdade. O tamanho do quadradinho muda com o zoom do mapa.
 *
 * É o mapa do próprio motorista: mostra onde **ele** recebeu chamadas. Oferta cujo endereço o
 * Geocoder não localizou não tem coordenada e fica de fora (o rodapé diz quantas entraram).
 */
@Composable
internal fun HeatMapTab() {
    val ctx = LocalContext.current
    val repos = MappApp.repos
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(HeatFilter()) }
    var zoom by remember { mutableStateOf(MapViews.CITY_ZOOM) }
    var selected by remember { mutableStateOf<HeatZone?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var toSave by remember { mutableStateOf<HeatZone?>(null) }
    /** Zona com o contorno grosso (tocada na lista); o toque no mapa abre os números dela. */
    var highlighted by remember { mutableStateOf<HeatZone?>(null) }
    var framed by remember { mutableStateOf(false) }

    // Fixo enquanto a aba está aberta: o período não pode andar sozinho a cada recomposição.
    val now = remember { System.currentTimeMillis() }
    val records by remember(filter.period) {
        repos.offers.between(filter.period.from(now), now)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val myZones by remember { repos.zones.all() }.collectAsStateWithLifecycle(initialValue = emptyList())

    // A conta da grade roda fora da thread da tela: com o histórico grande (milhares de ofertas) ela
    // levaria um tempo visível a cada zoom, e a tela travaria junto.
    var heat by remember { mutableStateOf(HeatState()) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(records, filter, zoom) {
        working = true
        heat = withContext(Dispatchers.Default) { computeHeat(records, filter, zoom, now) }
        working = false
    }
    val grid = heat.grid
    val scale = heat.scale
    val detected = heat.zones
    val categories = heat.categories
    val categoryColors = heat.categoryColors

    // Nome do bairro de cada zona (Geocoder do sistema, melhor esforço).
    val names = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(detected) {
        detected.forEach { zone ->
            val key = zoneKey(zone)
            if (!names.containsKey(key)) {
                val name = GeocodingRepository.get(ctx).neighborhood(zone.center)
                names[key] = name ?: ""
            }
        }
    }

    val mapView = remember { MapViews.create(ctx, interactive = true).apply { setDestroyMode(false) } }
    val overlay = remember { HeatOverlay(ctx.resources.displayMetrics.density) }
    val tapOverlay = remember {
        org.osmdroid.views.overlay.MapEventsOverlay(
            object : org.osmdroid.events.MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint?): Boolean {
                    val point = p?.toCore() ?: return false
                    val hit = overlay.zones.firstOrNull { Geo.contains(it.polygon, point) }
                    selected = hit
                    highlighted = hit
                    return hit != null
                }

                override fun longPressHelper(p: org.osmdroid.util.GeoPoint?): Boolean = false
            },
        )
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
        // O quadradinho muda de tamanho com o zoom: a grade é refeita a cada mudança.
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false

            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                val level = event?.zoomLevel ?: return false
                // Degraus de 1/4 de zoom: o hexágono acompanha a tela sem refazer a grade à toa.
                val step = kotlin.math.round(level * 4.0) / 4.0
                if (kotlin.math.abs(step - zoom) >= 0.01) zoom = step
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
            mapView.onDetach()
        }
    }

    LaunchedEffect(grid.cells.size) {
        if (framed) return@LaunchedEffect
        if (grid.cells.isNotEmpty()) {
            MapViews.frame(mapView, grid.cells.map { it.center })
            zoom = mapView.zoomLevelDouble
            framed = true
        } else if (!working) {
            LocationProvider.get(ctx).current()?.let { MapViews.centerOn(mapView, it) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MetricChips(filter.metric) { filter = filter.copy(metric = it) }
        FilterChips(
            filter = filter,
            onOpen = { showFilters = true },
            onSet = { filter = filter.copy(set = it) },
        )
        if (working) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(2.dp),
                color = MappColors.Teal,
                trackColor = Color.Transparent,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { m ->
                    overlay.cells = grid.cells
                    overlay.zones = detected
                    overlay.scale = scale
                    overlay.metric = filter.metric
                    overlay.categoryColors = categoryColors
                    overlay.highlighted = highlighted ?: selected
                    MapViews.clear(m)
                    m.overlays.add(overlay)
                    // As zonas desenhadas à mão ficam por cima, para dar para comparar.
                    MapViews.addZones(m, myZones)
                    m.overlays.add(tapOverlay)
                    m.invalidate()
                },
            )
            Legend(
                metric = filter.metric,
                categories = categories.take(6),
                categoryColors = categoryColors,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        LocationProvider.get(ctx).current()?.let {
                            MapViews.centerOn(mapView, it, zoom = mapView.zoomLevelDouble)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = "Minha posição")
            }
        }

        Surface(color = MappColors.Navy) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    footer(grid, detected.size, heat.located, records.size),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
                HorizontalDivider()
                if (detected.isEmpty()) {
                    Text(
                        if (working) "Montando o mapa…" else emptyMessage(heat.located, grid.cells.size),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Zonas detectadas (${detected.size})",
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        itemsIndexed(detected) { index, zone ->
                            ZoneRow(
                                zone = zone,
                                name = zoneName(zone, names[zoneKey(zone)], index),
                                metric = filter.metric,
                                onClick = {
                                    highlighted = zone
                                    MapViews.frame(mapView, zone.polygon)
                                },
                                onSave = { toSave = zone },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        HeatFilterDialog(
            filter = filter,
            categories = categories,
            onDismiss = { showFilters = false },
            onConfirm = {
                filter = it
                showFilters = false
            },
        )
    }

    selected?.let { zone ->
        ZoneDetailsDialog(
            zone = zone,
            name = zoneName(zone, names[zoneKey(zone)], detected.indexOf(zone)),
            onDismiss = { selected = null },
            onSave = {
                selected = null
                toSave = zone
            },
        )
    }

    toSave?.let { zone ->
        val suggested = zoneName(zone, names[zoneKey(zone)], detected.indexOf(zone))
        SaveZoneDialog(
            initialName = suggested,
            initialType = zone.suggestedType,
            onDismiss = { toSave = null },
            onConfirm = { name, type, alert ->
                toSave = null
                scope.launch {
                    repos.zones.save(
                        Zone(name = name, type = type, polygon = zone.polygon, alertEnabled = alert),
                    )
                }
            },
        )
    }
}

/** Tudo o que a conta da grade devolve de uma vez (ela roda fora da thread da tela). */
private data class HeatState(
    val grid: HeatMap = HeatMap.EMPTY,
    val scale: HeatScale? = null,
    val zones: List<HeatZone> = emptyList(),
    val categories: List<String> = emptyList(),
    val categoryColors: Map<String, Long> = emptyMap(),
    /** Ofertas do período com coordenada (as outras não entram no mapa). */
    val located: Int = 0,
)

/**
 * Histórico → grade de hexágonos → zonas. Roda em [Dispatchers.Default]; os `yield()` deixam o
 * trabalho ser cancelado quando o motorista mexe no zoom ou nos filtros antes de terminar.
 */
private suspend fun computeHeat(
    records: List<OfferRecord>,
    filter: HeatFilter,
    zoom: Double,
    now: Long,
): HeatState {
    val points = records.mapNotNull { HeatPoints.of(it.evaluated, it.accepted, filter.spot) }
    yield()
    val shown = HeatPoints.filter(points, filter, now)
    yield()
    val grid = HeatGrid.build(shown, zoom)
    yield()
    val scale = HeatScale.of(grid.cells, filter.metric)
    val zones = HeatZones.of(grid, scale, filter.metric)
    val categories = HeatPoints.categories(points)
    return HeatState(
        grid = grid,
        scale = scale,
        zones = zones,
        categories = categories,
        categoryColors = HeatPalette.categoryColors(categories),
        located = points.size,
    )
}

/** Botões de métrica: o que o mapa pinta. */
@Composable
private fun MetricChips(current: HeatMetric, onPick: (HeatMetric) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeatMetric.entries.forEach { m ->
            FilterChip(selected = m == current, onClick = { onPick(m) }, label = { Text(m.label) })
        }
    }
}

/** Filtros: tocar em qualquer um abre o diálogo; "Todas/Só aceitas" troca na hora. */
@Composable
private fun FilterChips(filter: HeatFilter, onOpen: () -> Unit, onSet: (HeatSet) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.set == HeatSet.ACCEPTED,
            onClick = { onSet(if (filter.set == HeatSet.ALL) HeatSet.ACCEPTED else HeatSet.ALL) },
            label = { Text(filter.set.label) },
        )
        FilterChip(selected = false, onClick = onOpen, label = { Text(filter.period.label) })
        FilterChip(selected = false, onClick = onOpen, label = { Text(filter.hours?.label ?: "Todo dia") })
        FilterChip(
            selected = false,
            onClick = onOpen,
            label = { Text(if (filter.platforms.isEmpty()) "Todos os apps" else filter.platforms.joinToString(", ") { it.label }) },
        )
        FilterChip(
            selected = false,
            onClick = onOpen,
            label = { Text(if (filter.categories.isEmpty()) "Toda modalidade" else filter.categories.joinToString(", ")) },
        )
        FilterChip(selected = false, onClick = onOpen, label = { Text(filter.spot.label) })
    }
}

/** Legenda: frio → quente, ou uma cor por modalidade. */
@Composable
private fun Legend(
    metric: HeatMetric,
    categories: List<String>,
    categoryColors: Map<String, Long>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MappColors.Navy.copy(alpha = 0.88f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (metric == HeatMetric.CATEGORY) {
                if (categories.isEmpty()) {
                    Text("Sem modalidade lida", style = MaterialTheme.typography.labelSmall, color = MappColors.TextDim)
                }
                categories.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(Color(categoryColors[name]?.toInt() ?: 0), CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = MappColors.Text)
                    }
                }
            } else {
                Text(
                    if (metric == HeatMetric.CALLS) "chamadas" else metric.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MappColors.TextDim,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("−", style = MaterialTheme.typography.labelSmall, color = MappColors.TextDim)
                    Spacer(Modifier.width(4.dp))
                    (0 until HeatPalette.LEVELS).forEach { level ->
                        Box(
                            Modifier
                                .size(width = 14.dp, height = 10.dp)
                                .background(Color(HeatPalette.solid(HeatPalette.levelColor(metric, level)).toInt())),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("+", style = MaterialTheme.typography.labelSmall, color = MappColors.TextDim)
                }
            }
        }
    }
}

/** Uma zona detectada na lista. */
@Composable
private fun ZoneRow(
    zone: HeatZone,
    name: String,
    metric: HeatMetric,
    onClick: () -> Unit,
    onSave: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier
                    .size(14.dp)
                    .background(
                        Color(HeatPalette.solid(HeatPalette.levelColor(metric, HeatPalette.LEVELS - 1)).toInt()),
                        CircleShape,
                    ),
            )
        },
        headlineContent = { Text(name) },
        supportingContent = { Text(zoneNumbers(zone)) },
        trailingContent = {
            OutlinedButton(onClick = onSave) { Text("Salvar") }
        },
    )
}

/** Toque na zona no mapa: os números dela e o botão de salvar. */
@Composable
private fun ZoneDetailsDialog(zone: HeatZone, name: String, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${zone.count} chamada(s) · ${zone.accepted} aceita(s)")
                Text("R\$/km médio: ${Format.perKm(zone.avg(HeatMetric.PER_KM))}")
                Text("R\$/h médio: ${Format.perHour(zone.avg(HeatMetric.PER_HOUR))}")
                Text("Lucro médio: ${Format.brl(zone.avg(HeatMetric.PROFIT))}")
                Text("Valor médio: ${Format.brl(zone.avg(HeatMetric.PRICE))}")
                zone.topCategory?.let { Text("Modalidade mais vista: $it") }
                Text(
                    "Área de ${zone.cells.size} quadradinho(s) da grade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Salvar como zona") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

/** Diálogo com todos os filtros do mapa de calor. */
@Composable
private fun HeatFilterDialog(
    filter: HeatFilter,
    categories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (HeatFilter) -> Unit,
) {
    var draft by remember { mutableStateOf(filter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtros do mapa") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Período", style = MaterialTheme.typography.titleSmall)
                HeatPeriod.entries.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { draft = draft.copy(period = p) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = draft.period == p, onClick = { draft = draft.copy(period = p) })
                        Text(p.label)
                    }
                }

                Text("Horário", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HOUR_PRESETS.forEach { (label, hours) ->
                        FilterChip(
                            selected = draft.hours == hours || (hours == null && draft.hours == null),
                            onClick = { draft = draft.copy(hours = hours) },
                            label = { Text(label) },
                        )
                    }
                }

                Text("App", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platform.entries.forEach { p ->
                        FilterChip(
                            selected = p in draft.platforms,
                            onClick = {
                                val next = draft.platforms.toMutableSet()
                                if (!next.remove(p)) next.add(p)
                                draft = draft.copy(platforms = next)
                            },
                            label = { Text(p.label) },
                        )
                    }
                }

                if (categories.isNotEmpty()) {
                    Text("Modalidade", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { c ->
                            FilterChip(
                                selected = c in draft.categories,
                                onClick = {
                                    val next = draft.categories.toMutableSet()
                                    if (!next.remove(c)) next.add(c)
                                    draft = draft.copy(categories = next)
                                },
                                label = { Text(c) },
                            )
                        }
                    }
                }

                Text("Ponto da corrida", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeatSpot.entries.forEach { s ->
                        FilterChip(
                            selected = draft.spot == s,
                            onClick = { draft = draft.copy(spot = s) },
                            label = { Text(s.label) },
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Só corridas aceitas", Modifier.weight(1f))
                    Switch(
                        checked = draft.set == HeatSet.ACCEPTED,
                        onCheckedChange = { draft = draft.copy(set = if (it) HeatSet.ACCEPTED else HeatSet.ALL) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft) }) { Text("Aplicar") } },
        dismissButton = {
            TextButton(onClick = { onConfirm(HeatFilter(metric = draft.metric)) }) { Text("Limpar") }
        },
    )
}

private val HOUR_PRESETS: List<Pair<String, HeatHours?>> = listOf(
    "Todo dia" to null,
    "05h–11h" to HeatHours(5, 11),
    "11h–17h" to HeatHours(11, 17),
    "17h–23h" to HeatHours(17, 23),
    "23h–05h" to HeatHours(23, 5),
)

private fun zoneKey(zone: HeatZone): String {
    val lat = (zone.center.lat * 1000).toInt()
    val lon = (zone.center.lon * 1000).toInt()
    return "$lat,$lon,${zone.cells.size}"
}

/** Nome do bairro quando o Geocoder acha; senão "Zona 1", "Zona 2"… */
private fun zoneName(zone: HeatZone, found: String?, index: Int): String =
    found?.takeIf { it.isNotBlank() } ?: "Zona ${if (index >= 0) index + 1 else zone.cells.size}"

private fun zoneNumbers(zone: HeatZone): String = buildString {
    append("${zone.count} chamada(s)")
    zone.avg(HeatMetric.PER_KM)?.let { append(" · ").append(Format.perKm(it)) }
    zone.avg(HeatMetric.PER_HOUR)?.let { append(" · ").append(Format.perHour(it)) }
    if (zone.accepted > 0) append(" · ${zone.accepted} aceita(s)")
}

private fun footer(grid: HeatMap, zones: Int, withPoint: Int, records: Int): String {
    val lost = records - withPoint
    return buildString {
        append("${grid.plotted} de ${grid.considered} oferta(s) no mapa")
        if (zones > 0) append(" · $zones zona(s)")
        if (!grid.isEmpty) append(" · hex de ${hexSize(grid.sizeMeters)}")
        if (lost > 0) append(" · $lost sem endereço localizado")
    }
}

/** "~600 m" ou "~1,5 km", para dar para ver o hexágono encolhendo com o zoom. */
private fun hexSize(size: Double): String {
    val meters = size * HexMath.SQRT3
    return if (meters >= 1_000) "~${Format.input((meters / 1_000).roundTo1())} km" else "~${meters.toInt()} m"
}

private fun Double.roundTo1(): Double = kotlin.math.round(this * 10) / 10

private fun emptyMessage(points: Int, cells: Int): String = when {
    points == 0 ->
        "Nenhuma oferta com endereço localizado no período. O mapa usa a coordenada da coleta " +
            "(ou do destino): ofertas cujo endereço o GPS não localizou ficam de fora."
    cells == 0 ->
        "Ainda não há ofertas suficientes perto umas das outras. A mancha só aparece onde houver " +
            "uma dupla de chamadas por perto, para uma corrida sortuda não virar zona de ouro."
    else ->
        "A mancha está pareja: nenhuma região se destaca nesta métrica. " +
            "Troque a métrica, o período ou dê zoom para separar melhor."
}
