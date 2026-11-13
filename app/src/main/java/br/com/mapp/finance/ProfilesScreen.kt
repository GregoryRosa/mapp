@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.filter.ThresholdRange
import br.com.mapp.core.filter.ThresholdScale
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.ui.GlassCard
import br.com.mapp.ui.MappColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ProfilesScreen(onBack: () -> Unit) {
    val repos = MappApp.repos
    val scope = rememberCoroutineScope()
    val profiles by remember { repos.profiles.all() }.collectAsStateWithLifecycle(initialValue = emptyList())

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FilterProfile?>(null) }
    var toDelete by remember { mutableStateOf<FilterProfile?>(null) }

    Scaffold(
        topBar = { BackTopBar("Perfis de filtro", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Novo perfil") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { editing = null; showEditor = true },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "O perfil ativo define o semáforo das ofertas. Campos em branco desligam o critério.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (profiles.isEmpty()) {
                item {
                    EmptyText(
                        "Nenhum perfil salvo. Usando o padrão: " + describe(FilterProfile()) +
                            ". Crie um perfil para personalizar."
                    )
                }
            }
            items(profiles, key = { it.id }) { p ->
                ProfileCard(
                    profile = p,
                    onActivate = { scope.launch { repos.profiles.setActive(p.id) } },
                    onEdit = { editing = p; showEditor = true },
                    onDelete = { toDelete = p },
                )
            }
        }
    }

    if (showEditor) {
        ProfileEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { p ->
                // Novo perfil só nasce ativo se for o primeiro.
                val toSave = if (editing == null) p.copy(active = profiles.isEmpty()) else p
                scope.launch { repos.profiles.save(toSave) }
                showEditor = false
            },
        )
    }
    toDelete?.let { p ->
        ConfirmDeleteDialog(
            title = "Excluir perfil?",
            text = "O perfil \"${p.name}\" será removido." +
                if (p.active) " Outro perfil será ativado automaticamente." else "",
            onConfirm = { scope.launch { repos.profiles.delete(p.id) } },
            onDismiss = { toDelete = null },
        )
    }
}

private fun describe(p: FilterProfile): String = buildList {
    if (p.minPerKm != null || p.goodPerKm != null) add("R$/km ${Format.input(p.minPerKm).ifEmpty { "—" }} / ${Format.input(p.goodPerKm).ifEmpty { "—" }}")
    if (p.minPerHour != null || p.goodPerHour != null) add("R$/h ${Format.input(p.minPerHour).ifEmpty { "—" }} / ${Format.input(p.goodPerHour).ifEmpty { "—" }}")
    p.minPrice?.let { add("mín. ${Format.brl(it)}") }
    p.maxPickupKm?.let { add("busca ≤ ${Format.input(it)} km") }
    p.maxPickupMin?.let { add("busca ≤ $it min") }
    p.minRating?.let { add("nota ≥ ${Format.input(it)}") }
}.joinToString(" · ").ifEmpty { "sem critérios" }

@Composable
private fun ProfileCard(profile: FilterProfile, onActivate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onActivate)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = profile.active, onClick = onActivate)
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name + if (profile.active) " (ativo)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(describe(profile), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Editar") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Excluir") }
        }
    }
}

/**
 * Editor do perfil em tela cheia, no estilo do print de referência: cada critério é um slider
 * vermelho/amarelo/verde. Tocar no valor em cima do botão abre o teclado para digitar; o slider
 * acompanha o número digitado (e estica a escala se ele passar da ponta). Chave desligada = critério
 * fora do semáforo (o mesmo que deixar o campo em branco antes).
 */
@Composable
private fun ProfileEditorDialog(initial: FilterProfile?, onDismiss: () -> Unit, onSave: (FilterProfile) -> Unit) {
    val base = initial ?: FilterProfile(name = "")
    var name by remember { mutableStateOf(base.name) }
    var perKm by remember { mutableStateOf(ThresholdRange.of(base.minPerKm, base.goodPerKm)) }
    var perHour by remember { mutableStateOf(ThresholdRange.of(base.minPerHour, base.goodPerHour)) }
    var rating by remember { mutableStateOf(base.minRating) }
    var minPrice by remember { mutableStateOf(base.minPrice) }
    var pickupKm by remember { mutableStateOf(base.maxPickupKm) }
    var pickupMin by remember { mutableStateOf(base.maxPickupMin?.toDouble()) }

    fun save() = onSave(
        base.copy(
            name = name.trim(),
            minPerKm = perKm?.low,
            goodPerKm = perKm?.high,
            minPerHour = perHour?.low,
            goodPerHour = perHour?.high,
            minRating = rating,
            minPrice = minPrice,
            maxPickupKm = pickupKm,
            maxPickupMin = pickupMin?.roundToInt(),
        ),
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MappColors.backgroundBrush)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancelar") }
                    Text(
                        if (initial == null) "Novo perfil" else "Editar perfil",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(enabled = name.isNotBlank(), onClick = ::save) { Text("Salvar") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome (ex.: Dia, Noite, Aeroporto)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Defina o que é corrida boa pra você. Toque no valor para digitar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MappColors.TextDim,
                    )
                    RangeCard(
                        title = "Valor por km",
                        icon = Icons.Outlined.AttachMoney,
                        scale = ThresholdScale.PER_KM,
                        range = perKm,
                        default = ThresholdRange(1.50, 2.00),
                        format = { Format.brl(it) },
                        onChange = { perKm = it },
                    )
                    RangeCard(
                        title = "Valor por hora",
                        icon = Icons.Outlined.Schedule,
                        scale = ThresholdScale.PER_HOUR,
                        range = perHour,
                        default = ThresholdRange(30.0, 45.0),
                        format = { "R$ " + Format.input(it) },
                        onChange = { perHour = it },
                    )
                    SingleCard(
                        title = "Nota do passageiro",
                        icon = Icons.Outlined.StarBorder,
                        kind = SliderKind.AT_LEAST,
                        scale = ThresholdScale.RATING,
                        value = rating,
                        default = 4.70,
                        format = { String.format(Format.PT_BR, "%.2f", it) },
                        caption = { "Vermelho abaixo de $it" },
                        onChange = { rating = it },
                    )
                    SingleCard(
                        title = "Valor mínimo da corrida",
                        icon = Icons.Outlined.Payments,
                        kind = SliderKind.AT_LEAST,
                        scale = ThresholdScale.MIN_PRICE,
                        value = minPrice,
                        default = 8.0,
                        format = { Format.brl(it) },
                        caption = { "Vermelho abaixo de $it" },
                        onChange = { minPrice = it },
                    )
                    SingleCard(
                        title = "Busca máxima (km)",
                        icon = Icons.Outlined.NearMe,
                        kind = SliderKind.AT_MOST,
                        scale = ThresholdScale.PICKUP_KM,
                        value = pickupKm,
                        default = 4.0,
                        format = { Format.km(it) },
                        caption = { "Vermelho acima de $it" },
                        onChange = { pickupKm = it },
                    )
                    SingleCard(
                        title = "Busca máxima (minutos)",
                        icon = Icons.Outlined.Timer,
                        kind = SliderKind.AT_MOST,
                        scale = ThresholdScale.PICKUP_MIN,
                        value = pickupMin,
                        default = 8.0,
                        format = { "${it.roundToInt()} min" },
                        caption = { "Vermelho acima de $it" },
                        onChange = { pickupMin = it?.let { v -> v.roundToInt().toDouble() } },
                        integer = true,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sliders

private val SliderRed = Color(0xFFD24B4B)
private val SliderYellow = Color(0xFFF2C14E)
private val SliderGreen = Color(0xFF9ACD45)
private val SliderOff = Color(0xFF3C5E68)

/** Como o trilho é pintado. */
private enum class SliderKind {
    /** Dois botões: vermelho | amarelo | verde (mínimo e bom). */
    RANGE,
    /** Um botão, quanto mais melhor: vermelho | verde. */
    AT_LEAST,
    /** Um botão, quanto menos melhor: verde | vermelho. */
    AT_MOST,
}

/** Critério com faixa amarela (R$/km, R$/hora). */
@Composable
private fun RangeCard(
    title: String,
    icon: ImageVector,
    scale: ThresholdScale,
    range: ThresholdRange?,
    default: ThresholdRange,
    format: (Double) -> String,
    onChange: (ThresholdRange?) -> Unit,
) {
    val shown = range ?: default
    ThresholdCard(
        title = title,
        icon = icon,
        kind = SliderKind.RANGE,
        baseScale = scale,
        values = listOf(shown.low, shown.high),
        enabled = range != null,
        format = format,
        caption = {
            buildAnnotatedString {
                withStyle(SpanStyle(color = SliderYellow, fontWeight = FontWeight.SemiBold)) { append("Amarelo") }
                append(" de ${format(shown.low)}  ·  ")
                withStyle(SpanStyle(color = SliderGreen, fontWeight = FontWeight.SemiBold)) { append("Verde") }
                append(" de ${format(shown.high)}")
            }
        },
        onToggle = { on -> onChange(if (on) shown else null) },
        onValue = { index, v -> onChange(if (index == 0) shown.withLow(v) else shown.withHigh(v)) },
    )
}

/** Critério de um valor só (nota, valor mínimo, busca máxima). */
@Composable
private fun SingleCard(
    title: String,
    icon: ImageVector,
    kind: SliderKind,
    scale: ThresholdScale,
    value: Double?,
    default: Double,
    format: (Double) -> String,
    caption: (String) -> String,
    onChange: (Double?) -> Unit,
    integer: Boolean = false,
) {
    val shown = value ?: default
    ThresholdCard(
        title = title,
        icon = icon,
        kind = kind,
        baseScale = scale,
        values = listOf(shown),
        enabled = value != null,
        format = format,
        caption = { AnnotatedString(caption(format(shown))) },
        onToggle = { on -> onChange(if (on) shown else null) },
        onValue = { _, v -> onChange(v) },
        integer = integer,
    )
}

@Composable
private fun ThresholdCard(
    title: String,
    icon: ImageVector,
    kind: SliderKind,
    baseScale: ThresholdScale,
    values: List<Double>,
    enabled: Boolean,
    format: (Double) -> String,
    caption: () -> AnnotatedString,
    onToggle: (Boolean) -> Unit,
    onValue: (index: Int, value: Double) -> Unit,
    integer: Boolean = false,
) {
    // A escala cresce para caber o que foi digitado (o slider acompanha) e não encolhe de volta.
    var scale by remember { mutableStateOf(baseScale.fitting(*values.toTypedArray())) }
    var typing by remember { mutableStateOf<Int?>(null) }
    val colors = thumbColors(kind, enabled)

    GlassCard(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MappColors.Text) }
            Spacer(Modifier.width(14.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(10.dp))
        ThumbLabels(fractions = values.map { scale.fraction(it).toFloat() }, thumbRadius = THUMB_RADIUS) {
            values.forEachIndexed { i, v ->
                Text(
                    format(v),
                    color = colors[i],
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { typing = i }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        ThresholdTrack(
            scale = scale,
            values = values,
            kind = kind,
            enabled = enabled,
            onValue = { i, v ->
                if (!enabled) onToggle(true)
                onValue(i, v)
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (enabled) caption() else AnnotatedString("Desligado: não entra no semáforo"),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MappColors.TextDim,
            textAlign = TextAlign.Center,
        )
    }

    typing?.let { i ->
        TypeValueDialog(
            title = title,
            initial = values[i],
            integer = integer,
            onDismiss = { typing = null },
            onConfirm = { v ->
                typing = null
                scale = scale.fitting(v)
                if (!enabled) onToggle(true)
                onValue(i, v)
            },
        )
    }
}

/** Cor do número e da borda de cada botão (o 1º da faixa é o amarelo). */
private fun thumbColors(kind: SliderKind, enabled: Boolean): List<Color> = when {
    !enabled -> listOf(MappColors.TextDim, MappColors.TextDim)
    kind == SliderKind.RANGE -> listOf(SliderYellow, SliderGreen)
    else -> listOf(SliderGreen)
}

private val THUMB_RADIUS = 14.dp
private val TRACK_HEIGHT = 10.dp

/** Coloca cada número em cima do seu botão, sem sair da tela e sem um cobrir o outro. */
@Composable
private fun ThumbLabels(fractions: List<Float>, thumbRadius: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val r = thumbRadius.toPx()
        val gap = 8.dp.toPx()
        val xs = placeables.mapIndexed { i, p ->
            val center = r + fractions.getOrElse(i) { 0f } * (width - 2 * r)
            (center - p.width / 2f).coerceIn(0f, (width - p.width).toFloat().coerceAtLeast(0f))
        }.toMutableList()
        if (placeables.size == 2) {
            val overlap = xs[0] + placeables[0].width + gap - xs[1]
            if (overlap > 0) {
                xs[0] -= overlap / 2
                xs[1] += overlap / 2
                if (xs[0] < 0) { xs[1] -= xs[0]; xs[0] = 0f }
                val over = xs[1] + placeables[1].width - width
                if (over > 0) { xs[0] -= over; xs[1] -= over }
            }
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.place(xs[i].roundToInt(), 0) }
        }
    }
}

/** Trilho colorido com 1 ou 2 botões; arrastar ou tocar move o botão mais perto. */
@Composable
private fun ThresholdTrack(
    scale: ThresholdScale,
    values: List<Double>,
    kind: SliderKind,
    enabled: Boolean,
    onValue: (Int, Double) -> Unit,
) {
    val current by rememberUpdatedState(values)
    val currentScale by rememberUpdatedState(scale)
    val emit by rememberUpdatedState(onValue)
    val colors = thumbColors(kind, enabled)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(THUMB_RADIUS * 2 + 8.dp)
            .pointerInput(Unit) {
                val r = THUMB_RADIUS.toPx()
                fun fractionAt(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f).toDouble()
                fun xOf(v: Double) = r + currentScale.fraction(v).toFloat() * (size.width - 2 * r)
                fun pick(x: Float): Int {
                    val vs = current
                    if (vs.size < 2) return 0
                    val a = xOf(vs[0])
                    val b = xOf(vs[1])
                    return when {
                        abs(a - b) < 1f -> if (x < a) 0 else 1 // juntos: o lado do toque decide
                        abs(x - a) <= abs(x - b) -> 0
                        else -> 1
                    }
                }
                fun move(i: Int, x: Float) {
                    val v = currentScale.valueAt(fractionAt(x))
                    if (v != current.getOrNull(i)) emit(i, v)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val thumb = pick(down.position.x)
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (start != null) {
                        move(thumb, start.position.x)
                        horizontalDrag(start.id) { change ->
                            move(thumb, change.position.x)
                            change.consume()
                        }
                    } else {
                        // Toque sem arrastar (e sem rolar a tela): o botão pula para lá.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) move(thumb, down.position.x)
                    }
                }
            },
    ) {
        val r = THUMB_RADIUS.toPx()
        val h = TRACK_HEIGHT.toPx()
        val cy = size.height / 2
        val left = r
        val right = size.width - r
        val xs = values.map { left + scale.fraction(it).toFloat() * (right - left) }
        val segments: List<Pair<Float, Color>> = if (!enabled) {
            listOf(right to SliderOff)
        } else {
            when (kind) {
                SliderKind.RANGE -> listOf(xs[0] to SliderRed, xs[1] to SliderYellow, right to SliderGreen)
                SliderKind.AT_LEAST -> listOf(xs[0] to SliderRed, right to SliderGreen)
                SliderKind.AT_MOST -> listOf(xs[0] to SliderGreen, right to SliderRed)
            }
        }
        // Trilho inteiro arredondado na cor da ponta; cada trecho por cima.
        drawRoundRect(
            segments.last().second,
            topLeft = Offset(left - h / 2, cy - h / 2),
            size = Size(right - left + h, h),
            cornerRadius = CornerRadius(h / 2),
        )
        var from = left - h / 2
        segments.dropLast(1).forEach { (to, color) ->
            if (to > from) {
                drawRoundRect(color, Offset(from, cy - h / 2), Size(to - from, h), CornerRadius(h / 2))
            }
            from = to
        }
        xs.forEachIndexed { i, x ->
            drawCircle(Color(0x55000000), radius = r + 1.dp.toPx(), center = Offset(x, cy + 1.dp.toPx()))
            drawCircle(Color.White, radius = r, center = Offset(x, cy))
            drawCircle(colors.getOrElse(i) { SliderGreen }, radius = r - 1.5.dp.toPx(), center = Offset(x, cy), style = Stroke(3.dp.toPx()))
        }
    }
}

/** Digitar o número exato: o slider vai junto. */
@Composable
private fun TypeValueDialog(
    title: String,
    initial: Double,
    integer: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(if (integer) initial.roundToInt().toString() else Format.input(initial)) }
    val focus = remember { FocusRequester() }
    val value = Format.parse(text)?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isNotBlank() && value == null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { value?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { value?.let(onConfirm) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
