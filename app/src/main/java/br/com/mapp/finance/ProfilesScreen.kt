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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import br.com.mapp.core.filter.FilterCriterion
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

private val SHORT = mapOf(
    FilterCriterion.PER_KM to "R$/km",
    FilterCriterion.PER_HOUR to "R$/h",
    FilterCriterion.RATING to "nota",
    FilterCriterion.PRICE to "valor",
    FilterCriterion.PICKUP_KM to "busca",
    FilterCriterion.PICKUP_MIN to "busca",
)

/** "R$/km R$ 1,50 → R$ 2,00 · busca 4,0 km ← 2,0 km ..." (amarelo → verde). */
private fun describe(p: FilterProfile): String = FilterCriterion.entries.mapNotNull { c ->
    val r = c.range(p) ?: return@mapNotNull null
    if (c.higherIsBetter) "${SHORT[c]} ${c.format(r.low)}→${c.format(r.high)}"
    else "${SHORT[c]} ≤${c.format(r.low)}/${c.format(r.high)}"
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
 * Editor do perfil em tela cheia, no estilo do print de referência: todo critério é um slider com
 * faixa vermelho/amarelo/verde e entra no semáforo (verde só com tudo verde). Tocar no valor em cima
 * do botão abre o teclado; o slider acompanha o número digitado (e estica a escala se passar da
 * ponta). Critério que o perfil ainda não tinha aparece com a faixa sugerida e vale ao salvar.
 */
@Composable
private fun ProfileEditorDialog(initial: FilterProfile?, onDismiss: () -> Unit, onSave: (FilterProfile) -> Unit) {
    val base = initial ?: FilterProfile(name = "")
    var name by remember { mutableStateOf(base.name) }
    val ranges = remember {
        mutableStateMapOf<FilterCriterion, ThresholdRange>().apply {
            FilterCriterion.entries.forEach { c -> put(c, c.range(base) ?: c.default) }
        }
    }

    fun save() = onSave(
        FilterCriterion.entries.fold(base.copy(name = name.trim())) { p, c -> c.apply(p, ranges[c]) },
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Diálogo não herda a cor do texto da tela: sem isso títulos e ícones saíam pretos no fundo escuro.
        CompositionLocalProvider(LocalContentColor provides MappColors.Text) {
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
                            color = MappColors.Text,
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
                            "Defina o que é corrida boa pra você. Todos os itens entram no semáforo: " +
                                "verde só com tudo verde. Toque no valor para digitar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MappColors.TextDim,
                        )
                        FilterCriterion.entries.forEach { c ->
                            CriterionCard(c, ranges.getValue(c)) { ranges[c] = it }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sliders

private val SliderRed = Color(0xFFD24B4B)
private val SliderYellow = Color(0xFFF2C14E)
private val SliderGreen = Color(0xFF9ACD45)

private fun FilterCriterion.icon(): ImageVector = when (this) {
    FilterCriterion.PER_KM -> Icons.Outlined.AttachMoney
    FilterCriterion.PER_HOUR -> Icons.Outlined.Schedule
    FilterCriterion.RATING -> Icons.Outlined.StarBorder
    FilterCriterion.PRICE -> Icons.Outlined.Payments
    FilterCriterion.PICKUP_KM -> Icons.Outlined.NearMe
    FilterCriterion.PICKUP_MIN -> Icons.Outlined.Timer
}

/** Cor de cada botão/número: mais é melhor = amarelo, verde; menos é melhor = verde, amarelo. */
private fun FilterCriterion.thumbColors(): List<Color> =
    if (higherIsBetter) listOf(SliderYellow, SliderGreen) else listOf(SliderGreen, SliderYellow)

/** Trechos do trilho, da esquerda para a direita. */
private fun FilterCriterion.segmentColors(): List<Color> =
    if (higherIsBetter) listOf(SliderRed, SliderYellow, SliderGreen) else listOf(SliderGreen, SliderYellow, SliderRed)

@Composable
private fun CriterionCard(criterion: FilterCriterion, range: ThresholdRange, onChange: (ThresholdRange) -> Unit) {
    // A escala cresce para caber o que foi digitado (o slider acompanha) e não encolhe de volta.
    var scale by remember { mutableStateOf(criterion.scale.fitting(range.low, range.high)) }
    var typing by remember { mutableStateOf<Int?>(null) }
    val colors = criterion.thumbColors()
    val values = listOf(range.low, range.high)
    fun set(index: Int, v: Double) = onChange(if (index == 0) range.withLow(v) else range.withHigh(v))

    GlassCard(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { Icon(criterion.icon(), contentDescription = null, tint = MappColors.Text) }
            Spacer(Modifier.width(14.dp))
            Text(
                criterion.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MappColors.Text,
            )
        }
        Spacer(Modifier.height(10.dp))
        ThumbLabels(fractions = values.map { scale.fraction(it).toFloat() }, thumbRadius = THUMB_RADIUS) {
            values.forEachIndexed { i, v ->
                Text(
                    criterion.format(v),
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
        ThresholdTrack(criterion, scale, values, onValue = ::set)
        Spacer(Modifier.height(6.dp))
        Text(
            caption(criterion, range),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MappColors.TextDim,
            textAlign = TextAlign.Center,
        )
    }

    typing?.let { i ->
        TypeValueDialog(
            title = criterion.title,
            initial = values[i],
            integer = criterion.integer,
            onDismiss = { typing = null },
            onConfirm = { v ->
                typing = null
                scale = scale.fitting(v)
                set(i, v)
            },
        )
    }
}

/** "Amarelo de R$ 1,60 · Verde de R$ 2,00" ou, na busca, "Verde até 2,0 km · Amarelo até 4,0 km". */
private fun caption(c: FilterCriterion, r: ThresholdRange): AnnotatedString = buildAnnotatedString {
    fun word(text: String, color: Color) = withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text) }
    if (c.higherIsBetter) {
        word("Amarelo", SliderYellow); append(" de ${c.format(r.low)}  ·  ")
        word("Verde", SliderGreen); append(" de ${c.format(r.high)}")
    } else {
        word("Verde", SliderGreen); append(" até ${c.format(r.low)}  ·  ")
        word("Amarelo", SliderYellow); append(" até ${c.format(r.high)}")
    }
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

/** Trilho colorido com 2 botões; arrastar ou tocar move o botão mais perto. */
@Composable
private fun ThresholdTrack(
    criterion: FilterCriterion,
    scale: ThresholdScale,
    values: List<Double>,
    onValue: (Int, Double) -> Unit,
) {
    val current by rememberUpdatedState(values)
    val currentScale by rememberUpdatedState(scale)
    val emit by rememberUpdatedState(onValue)
    val colors = criterion.thumbColors()
    val segments = criterion.segmentColors()
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
        // Trilho inteiro arredondado na cor da ponta direita; os trechos da esquerda por cima.
        drawRoundRect(
            segments[2],
            topLeft = Offset(left - h / 2, cy - h / 2),
            size = Size(right - left + h, h),
            cornerRadius = CornerRadius(h / 2),
        )
        if (xs[1] > xs[0]) {
            drawRect(segments[1], Offset(xs[0], cy - h / 2), Size(xs[1] - xs[0], h))
        }
        // Ponta esquerda arredondada; o fim de cada trecho fica embaixo do botão.
        drawRoundRect(segments[0], Offset(left - h / 2, cy - h / 2), Size(xs[0] - left + h / 2, h), CornerRadius(h / 2))
        xs.forEachIndexed { i, x ->
            drawCircle(Color(0x55000000), radius = r + 1.dp.toPx(), center = Offset(x, cy + 1.dp.toPx()))
            drawCircle(Color.White, radius = r, center = Offset(x, cy))
            drawCircle(colors[i], radius = r - 1.5.dp.toPx(), center = Offset(x, cy), style = Stroke(3.dp.toPx()))
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
