package br.com.mapp.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.goal.AdvanceDay
import br.com.mapp.core.input.MoneyInput
import br.com.mapp.core.goal.DayRange
import br.com.mapp.core.goal.GoalAdvance
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.CostBreakdown
import br.com.mapp.core.shift.CostShare
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.overlay.GoalAdvanceStore
import br.com.mapp.overlay.ShiftTracker
import br.com.mapp.ui.MappColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/* Componentes compartilhados pelas telas de finance/. */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
        },
        actions = actions,
    )
}

internal val GreenSignal = Color(0xFF43A047)
internal val YellowSignal = Color(0xFFFDD835)
internal val RedSignal = Color(0xFFE53935)

internal fun signalColor(signal: Signal): Color = when (signal) {
    Signal.GREEN -> GreenSignal
    Signal.YELLOW -> YellowSignal
    Signal.RED -> RedSignal
}

internal fun signalLabel(signal: Signal): String = when (signal) {
    Signal.GREEN -> "Verde"
    Signal.YELLOW -> "Amarelo"
    Signal.RED -> "Vermelho"
}

@Composable
internal fun SignalDot(signal: Signal, size: Dp = 14.dp) {
    Box(Modifier.size(size).background(signalColor(signal), CircleShape))
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}

/** Card pequeno com rótulo e valor. */
@Composable
internal fun StatCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
            )
        }
    }
}

/** Linha "rótulo | barra proporcional | valor". [max] <= 0 desenha barra vazia. */
@Composable
internal fun BarRow(
    label: String,
    value: Double,
    max: Double,
    valueText: String,
    color: Color = MaterialTheme.colorScheme.primary,
    valueWidth: Dp = 92.dp,
) {
    val fraction = if (max > 0) (value / max).toFloat().coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).height(16.dp).background(color, RoundedCornerShape(4.dp)))
            }
        }
        Text(valueText, Modifier.width(valueWidth), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

/** Campo numérico opcional (aceita vírgula). Fica em erro se não estiver vazio e não for número. */
@Composable
internal fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    integer: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val invalid = value.isNotBlank() && Format.parse(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = invalid,
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        trailingIcon = trailingIcon,
        supportingText = if (invalid) { { Text("Número inválido") } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Campo de dinheiro no estilo maquininha (`core/input/MoneyInput`): só números, o valor se forma
 * em centavos da direita para a esquerda (1773 → 17,73; 100000 → 1.000,00) e apagar tira o último
 * dígito. [value] e [onValueChange] usam o texto formatado ("17,73", vazio = sem valor), que o
 * `Format.parse` já lê — as telas guardam o mesmo texto de antes. [onDone] = tecla OK do teclado.
 */
@Composable
internal fun MoneyField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = "R$",
    trailingIcon: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    val digits = MoneyInput.digitsOf(value)
    OutlinedTextField(
        // O campo só tem os dígitos, sempre com o cursor no fim; quem mostra vírgula e ponto é o
        // MoneyTransformation.
        value = TextFieldValue(digits, selection = TextRange(digits.length)),
        onValueChange = { edited ->
            val next = MoneyInput.edit(digits, edited.text)
            val text = MoneyInput.format(next)
            if (text != value) onValueChange(text)
        },
        label = if (label != null) { { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
        singleLine = true,
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        trailingIcon = trailingIcon,
        visualTransformation = MoneyTransformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Mostra os dígitos (centavos) como dinheiro; o cursor fica sempre no fim. */
private object MoneyTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = MoneyInput.format(text.text)
        return TransformedText(
            AnnotatedString(shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = shown.length
                override fun transformedToOriginal(offset: Int): Int = text.length
            },
        )
    }
}

/** true se o texto está vazio ou é um número válido. */
internal fun isBlankOrNumber(text: String): Boolean = text.isBlank() || Format.parse(text) != null

@Composable
internal fun ConfirmDeleteDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Relógio da tela: atualiza a cada 30 s (tempo da jornada aberta, virada do dia). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Totais do período com a mesma conta em todas as telas ([PeriodMath.totals]): jornadas (km do GPS,
 * tempo, custo: desgaste por km + fixos em % do valor de cada corrida finalizada), corridas aceitas
 * fora de jornada e lançamentos manuais.
 */
@Composable
fun rememberPeriodTotals(period: Period, now: Long = rememberNow()): PeriodTotals {
    val ctx = LocalContext.current
    val repos = MappApp.repos
    val current by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    val history by remember { ShiftTracker.history(ctx) }.collectAsStateWithLifecycle()
    val cost by remember { repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    val offers by remember(period) { repos.offers.between(period.from, period.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList<OfferRecord>())
    val entries by remember(period) { repos.finance.entries(period.from, period.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList<FinanceEntry>())
    return remember(period, current, history, cost, offers, entries, now) {
        PeriodMath.totals(
            period.from, period.to, current, history, offers, entries, cost.costPerKm, now, CostSplit.parts(cost),
            cost.fixedShare,
        )
    }
}

/**
 * Adiantamento para as despesas visto do dia [date] (meta batida: `GoalAdvance`), com [totals] = os
 * números desse dia. Os custos dos dias anteriores, desde a 1ª corrida adiantada, vêm da mesma conta
 * do [rememberPeriodTotals]. Lucro do dia com ele = `GoalAdvance.net(totals.net, adiantamento)`.
 */
@Composable
fun rememberAdvanceDay(date: LocalDate, totals: PeriodTotals): AdvanceDay {
    val ctx = LocalContext.current
    val repos = MappApp.repos
    val book by remember { GoalAdvanceStore.book(ctx) }.collectAsStateWithLifecycle()
    val current by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    val history by remember { ShiftTracker.history(ctx) }.collectAsStateWithLifecycle()
    val cost by remember { repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    val day = date.toEpochDay()
    val days = remember(book, day) { GoalAdvance.daysBefore(book, day) }
    val ranges = remember(days) {
        days.map { d -> ReportMath.day(LocalDate.ofEpochDay(d)).let { DayRange(d, it.from, it.to) } }
    }
    val from = ranges.firstOrNull()?.from
    val to = ranges.lastOrNull()?.to
    val offers by remember(from, to) {
        if (from == null || to == null) flowOf(emptyList<OfferRecord>()) else repos.offers.acceptedBetween(from, to)
    }.collectAsStateWithLifecycle(initialValue = emptyList<OfferRecord>())
    val entries by remember(from, to) {
        if (from == null || to == null) flowOf(emptyList<FinanceEntry>()) else repos.finance.entries(from, to)
    }.collectAsStateWithLifecycle(initialValue = emptyList<FinanceEntry>())
    val past = remember(ranges, history, offers, entries, cost) {
        GoalAdvance.pastCosts(ranges, history, offers, entries, cost.costPerKm, System.currentTimeMillis())
    }
    return remember(book, current, history, past, totals, day) {
        val alive = GoalAdvance.alive(book.diversions, listOfNotNull(current) + history)
        GoalAdvance.day(alive, day, past + (day to totals.cost))
    }
}

/**
 * Totais de vários períodos fechados (meses, semanas da Uber) com uma leitura só do banco: corridas
 * aceitas e lançamentos do intervalo inteiro, divididos em memória. Mesma conta do [rememberPeriodTotals].
 */
@Composable
fun rememberPeriodsTotals(periods: List<Period>, now: Long = rememberNow()): List<PeriodTotals> {
    val ctx = LocalContext.current
    val repos = MappApp.repos
    val current by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    val history by remember { ShiftTracker.history(ctx) }.collectAsStateWithLifecycle()
    val cost by remember { repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    val from = periods.minOfOrNull { it.from } ?: 0L
    val to = periods.maxOfOrNull { it.to } ?: 0L
    val offers by remember(from, to) { repos.offers.acceptedBetween(from, to) }
        .collectAsStateWithLifecycle(initialValue = emptyList<OfferRecord>())
    val entries by remember(from, to) { repos.finance.entries(from, to) }
        .collectAsStateWithLifecycle(initialValue = emptyList<FinanceEntry>())
    // Período fechado só muda com o relógio se a jornada aberta começou dentro dele.
    val clock = if (current?.let { it.startedAt <= to } == true) now else 0L
    return remember(periods, current, history, cost, offers, entries, clock) {
        val parts = CostSplit.parts(cost)
        periods.map { p ->
            PeriodMath.totals(p.from, p.to, current, history, offers, entries, cost.costPerKm, now, parts, cost.fixedShare)
        }
    }
}

/** Cores das fatias do bruto: custos do carro na ordem de Custos fixos. */
private val CAR_COLORS = listOf(
    MappColors.Warn, Color(0xFFF0997B), MappColors.Lilac, MappColors.Periwinkle, MappColors.Aqua,
    Color(0xFFF06292), Color(0xFFAED581), Color(0xFF4FC3F7), Color(0xFFBA68C8), Color(0xFFA1887F),
)
private val EXPENSE_COLOR = MappColors.Loss
private val NET_COLOR = MappColors.Profit

/**
 * "Para onde foi o bruto (100%)": barra com as fatias e uma linha por custo — "Combustível (23%)" à
 * esquerda, o valor à direita —, os gastos lançados por categoria e a sobra. [km] = km do período
 * (base dos custos do carro).
 */
@Composable
internal fun CostBreakdownBlock(b: CostBreakdown, km: Double) {
    if (b.isEmpty) return
    val carColors = b.car.indices.map { CAR_COLORS[it % CAR_COLORS.size] }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (b.gross > 0) "Para onde foi o bruto (100%)" else "Custos do período (sem ganho bruto)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val slices = b.car.mapIndexed { i, s -> s.amount to carColors[i] } +
            listOfNotNull(b.expensesTotal?.let { it.amount to EXPENSE_COLOR }) +
            listOf(b.net.amount to NET_COLOR)
        val shown = slices.filter { it.first > 0 }
        if (shown.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).height(10.dp).clip(RoundedCornerShape(50)),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                shown.forEach { (w, c) -> Box(Modifier.weight(w.toFloat()).fillMaxHeight().background(c)) }
            }
        }
        b.car.forEachIndexed { i, s -> ShareRow(s, carColors[i]) }
        b.expensesTotal?.let { total ->
            ShareRow(total, EXPENSE_COLOR)
            b.expenses.forEach { ShareRow(it, dot = null, indent = true) }
        }
        if (b.fuelLaunched > 0) {
            Text(
                "⚠ ${Format.brl(b.fuelLaunched)} de combustível lançado em Gastos também já está em " +
                    "${b.car.firstOrNull()?.name ?: CostSplit.FUEL} (km × R$/km): esse valor conta duas vezes no custo.",
                style = MaterialTheme.typography.bodySmall,
                color = MappColors.Warn,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ShareRow(
            b.net,
            if (b.net.amount < 0) RedSignal else NET_COLOR,
            valueColor = if (b.net.amount < 0) RedSignal else GreenSignal,
            strong = true,
        )
        if (b.car.isNotEmpty()) {
            Text(
                "Custos do carro = ${Format.km(km)} × R$/km de cada item de Custos fixos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (b.splitEstimated) {
            Text(
                "≈ Jornadas antigas não guardaram a divisão do custo: o total do carro é o daquele dia, " +
                    "dividido pelas proporções dos Custos fixos de hoje.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "● Combustível (23%) ........ R$ 1.052,00". */
@Composable
private fun ShareRow(
    s: CostShare,
    dot: Color?,
    indent: Boolean = false,
    valueColor: Color = Color.Unspecified,
    strong: Boolean = false,
) {
    val style = if (indent) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val textColor = if (indent) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            Format.withPercent(s.name, s.percent),
            Modifier.weight(1f),
            style = style,
            color = if (strong) valueColor else textColor,
            fontWeight = if (strong) FontWeight.SemiBold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            Format.brl(s.amount),
            style = style,
            color = if (indent) textColor else valueColor,
            fontWeight = if (strong) FontWeight.SemiBold else null,
        )
    }
}

/**
 * Card menor de um período fechado (mês, semana da Uber), igual às jornadas: título e valor em cima,
 * resumo embaixo; toque abre [content]. [key] guarda aberto/fechado ao rolar e girar a tela.
 */
@Composable
internal fun ClosedPeriodCard(
    key: Long,
    title: String,
    value: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(value, fontWeight = FontWeight.Bold)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                content()
            }
        }
    }
}

/** Três números lado a lado (rótulo pequeno em cima), como na grade das jornadas. */
@Composable
internal fun StatRow(vararg stats: Triple<String, String, Color>) {
    Row(Modifier.fillMaxWidth()) {
        stats.forEach { (label, value, color) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Cor do lucro/líquido: vermelho no prejuízo. */
internal fun profitColor(v: Double): Color = if (v < 0) RedSignal else GreenSignal

/**
 * Botão "tirar os custos": alterna entre o valor bruto (padrão) e o líquido (bruto − custo do carro −
 * gastos lançados).
 */
@Composable
fun NetToggle(
    net: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = GreenSignal,
    idleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val color = if (net) activeColor else idleColor
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape)
            .background(if (net) color.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Calculate,
            contentDescription = if (net) "Mostrar valor bruto" else "Tirar os custos (valor líquido)",
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(if (net) "Líquido" else "Bruto", style = MaterialTheme.typography.labelMedium, color = color)
    }
}
