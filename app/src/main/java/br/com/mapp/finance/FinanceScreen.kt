@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import br.com.mapp.ui.Routes
import br.com.mapp.ui.SupportTab
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.overlay.OverlayPrefs
import br.com.mapp.overlay.ShiftTracker
import br.com.mapp.MappApp
import br.com.mapp.core.model.Platform
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.earnings.UberExtra
import br.com.mapp.core.goal.AdvanceDay
import br.com.mapp.core.goal.GoalAdvance
import br.com.mapp.core.goal.GoalForecast
import br.com.mapp.core.goal.GoalPace
import br.com.mapp.core.goal.GoalPlan
import br.com.mapp.core.goal.WeekGoals
import br.com.mapp.core.repo.Goals
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.ui.MappColors
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

private val EXPENSE_CATEGORIES = listOf("Combustível", "Manutenção", "Alimentação", "Lavagem", "Outros")
private const val OTHER_PLATFORM = "Outros"

/** Abas da tela Financeiro, com os mesmos ícones do menu da tela inicial. */
private enum class FinanceTab(val label: String, val icon: ImageVector) {
    EARNINGS("Ganhos e gastos", Icons.Outlined.AccountBalanceWallet),
    REPORTS("Relatórios", Icons.Outlined.BarChart),
    FIXED_COSTS("Custos fixos", Icons.Outlined.RequestQuote),
}

/** Tela Financeiro: abas Ganhos e gastos, Relatórios e Custos fixos (antes eram 3 telas do menu). */
@Composable
fun FinanceScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showGoals by remember { mutableStateOf(false) }
    SupportTab(Routes.FINANCE, FinanceTab.entries[tab].label)
    Scaffold(
        topBar = {
            BackTopBar("Financeiro", onBack) {
                if (tab == FinanceTab.EARNINGS.ordinal) {
                    IconButton(onClick = { showGoals = true }) { Icon(Icons.Filled.Flag, contentDescription = "Metas") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = MappColors.Teal) {
                FinanceTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        selectedContentColor = MappColors.Teal,
                        unselectedContentColor = MappColors.TextDim,
                    ) {
                        Icon(t.icon, contentDescription = null, modifier = Modifier.padding(top = 10.dp).size(22.dp))
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (FinanceTab.entries[tab]) {
                    FinanceTab.EARNINGS -> EarningsTab(showGoals = showGoals, onGoalsDone = { showGoals = false })
                    FinanceTab.REPORTS -> ReportsTab()
                    FinanceTab.FIXED_COSTS -> FixedCostsTab()
                }
            }
        }
    }
}

/** Aba Ganhos e gastos: Hoje, Semana, Mês e os lançamentos. Metas abrem pelo 🚩 da barra de cima. */
@Composable
private fun EarningsTab(showGoals: Boolean, onGoalsDone: () -> Unit) {
    val repos = MappApp.repos
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val ctx = LocalContext.current
    val openShift by remember { ShiftTracker.current(ctx) }.collectAsStateWithLifecycle()
    // "Hoje" = dia de trabalho: com jornada aberta depois da meia-noite, ainda é o dia em que ela começou.
    val todayDate = ReportMath.workDate(openShift?.startedAt, now, zone)
    // Dia escolhido no calendário da seção "Hoje" (null = hoje). Guardado como dia epoch.
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val date = pickedDay?.let { LocalDate.ofEpochDay(it) }?.takeIf { it <= todayDate } ?: todayDate
    // Períodos são data classes: recalculados a cada minuto, só mudam na virada do dia/semana/mês.
    val day = if (date == todayDate) ReportMath.workDay(openShift?.startedAt, now, zone) else ReportMath.day(date, zone)
    val week = ReportMath.uberWeek(now, zone)
    val month = ReportMath.currentMonth(now, zone)

    val entries by remember(month) { repos.finance.entries(month.from, month.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val goals by remember { repos.finance.goals() }.collectAsStateWithLifecycle(initialValue = Goals())

    // Mesma conta da tela inicial e do histórico: jornadas + corridas aceitas fora de jornada + lançamentos.
    val dayTotals = rememberPeriodTotals(day, now)
    // Meta batida: o lucro das corridas seguintes vai para as despesas dos próximos dias (GoalAdvance).
    val dayAdvance = rememberAdvanceDay(date, dayTotals)
    val weekTotals = rememberPeriodTotals(week, now)
    val monthTotals = rememberPeriodTotals(month, now)
    // Lucro do dia de trabalho de hoje (mesmo com outro dia escolhido no calendário): base do ritmo.
    val todayTotals = rememberPeriodTotals(ReportMath.workDay(openShift?.startedAt, now, zone), now)

    // Metas são de LUCRO LÍQUIDO (bruto − custo do carro − gastos lançados): é o que precisa sobrar.
    // Meta da semana = a meta mensal dividida pelas semanas (ou a soma das metas dos dias, sem mensal).
    val monday = ReportMath.dateOf(week.from, zone)
    val yearMonth = YearMonth.from(ReportMath.dateOf(month.from, zone))
    val weekGoal = remember(goals, monday) { GoalPlan.weekGoal(goals, monday) }
    val weekProgress = ReportMath.goalProgress(weekTotals.net, weekGoal?.amount)
    val monthProgress = ReportMath.goalProgress(monthTotals.net, goals.monthly)
    // Em que dia bate a meta no ritmo atual (ou quanto fica faltando).
    val weight = { d: LocalDate -> GoalPlan.weight(goals, d) }
    val weekForecast = weekProgress?.let {
        GoalPace.forecast(it.goal, weekTotals.net, todayTotals.net, GoalPlan.weekDays(monday), todayDate, weight)
    }
    val monthForecast = monthProgress?.let {
        GoalPace.forecast(it.goal, monthTotals.net, todayTotals.net, GoalPlan.daysOf(yearMonth), todayDate, weight)
    }
    val monthCosts = remember(monthTotals) { CostSplit.breakdown(monthTotals) }
    // Meses fechados (dia 1 ao último dia), do mais recente para trás; só os que tiveram movimento.
    val closedPeriods = remember(month) { ReportMath.closedMonths(CLOSED_MONTHS, month.from + 1, zone) }
    val closedTotals = rememberPeriodsTotals(closedPeriods, now)
    val closed = closedPeriods.zip(closedTotals).filter { !it.second.isEmpty }

    var showPicker by remember { mutableStateOf(false) }
    var showEntryDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FinanceEntry?>(null) }
    var toDelete by remember { mutableStateOf<FinanceEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PeriodCard(
                    title = dayTitle(date, todayDate),
                    subtitle = null,
                    totals = dayTotals,
                    // Com meta mensal, a parte dela no dia (igual à semana); sem ela, a meta do dia.
                    goal = ReportMath.goalProgress(GoalAdvance.net(dayTotals.net, dayAdvance), GoalPlan.dayGoal(goals, date)),
                    advance = dayAdvance,
                    goalHint = true,
                    titleActions = {
                        IconButton(onClick = { showPicker = true }) {
                            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Escolher o dia")
                        }
                        if (date != todayDate) {
                            TextButton(onClick = { pickedDay = null }) { Text("Hoje") }
                        }
                    },
                )
            }
            item {
                PeriodCard(
                    title = "Semana",
                    subtitle = "Seg ${Format.day(week.from)} às 4h até seg ${Format.day(week.to)} às 4h (fechamento da Uber)",
                    totals = weekTotals,
                    goal = weekProgress,
                    goalHint = true,
                    forecast = weekForecast?.let { forecastText(it, todayDate, "a semana") },
                    noGoalText = "Sem meta da semana: defina a meta mensal na bandeira (ela é dividida pelas semanas).",
                )
            }
            item {
                PeriodCard(
                    title = "Mês · ${ReportMath.monthName(month.from, zone)}",
                    subtitle = null,
                    totals = monthTotals,
                    goal = monthProgress,
                    goalHint = true,
                    forecast = monthForecast?.let { forecastText(it, todayDate, "o mês") },
                    footer = {
                        if (!monthCosts.isEmpty) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            CostBreakdownBlock(monthCosts, monthTotals.km)
                        }
                    },
                )
            }
            if (closed.isNotEmpty()) {
                item { SectionTitle("Meses fechados") }
                items(closed, key = { "mes-" + it.first.from }) { (p, t) -> ClosedMonthCard(p, t, zone) }
            }
            if (monthTotals.rides > 0) {
                item {
                    Text(
                        "Ganhos incluem ${monthTotals.rides} corridas do mês (${Format.brl(monthTotals.ridesGross)}), " +
                            "contadas sozinhas pela jornada e pelo histórico de ofertas. Não lance essas corridas de novo aqui.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { UberExtraRow() }
            item { SectionTitle("Lançamentos do mês") }
            if (entries.isEmpty()) {
                item { EmptyText("Nenhum lançamento neste mês. Toque em Lançar para registrar um ganho ou gasto.") }
            }
            items(entries, key = { it.id }) { e ->
                EntryRow(
                    entry = e,
                    onClick = { editing = e; showEntryDialog = true },
                    onDelete = { toDelete = e },
                )
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("Lançar") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { editing = null; showEntryDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (showPicker) {
        DayPickerDialog(
            selected = date,
            today = todayDate,
            onPick = { picked -> pickedDay = if (picked == todayDate) null else picked.toEpochDay() },
            onDismiss = { showPicker = false },
        )
    }
    if (showEntryDialog) {
        EntryDialog(
            initial = editing,
            onDismiss = { showEntryDialog = false },
            onSave = { entry ->
                scope.launch { repos.finance.save(entry) }
                showEntryDialog = false
            },
        )
    }
    if (showGoals) {
        GoalsDialog(
            goals = goals,
            month = yearMonth,
            monthLabel = ReportMath.monthName(month.from, zone),
            onDismiss = onGoalsDone,
            onSave = { g ->
                scope.launch { repos.finance.saveGoals(g) }
                onGoalsDone()
            },
        )
    }
    toDelete?.let { e ->
        ConfirmDeleteDialog(
            title = "Excluir lançamento?",
            text = "${e.category} · ${Format.brl(e.amount)} em ${Format.dayTime(e.atMillis)}",
            onConfirm = { scope.launch { repos.finance.delete(e.id) } },
            onDismiss = { toDelete = null },
        )
    }
}

/**
 * Chave do **ganho extra da Uber** automático: com o copiloto ligado, na tela inicial da Uber o MApp
 * compara o "Hoje" dela com as corridas contadas e lança a diferença (`overlay/UberExtraSync`).
 */
@Composable
private fun UberExtraRow() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(OverlayPrefs.isUberExtraOn(ctx)) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ganho extra da Uber sozinho", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (on) "Na tela inicial da Uber, o MApp compara o \"Hoje\" dela com as corridas contadas. " +
                        "O que a Uber pagou a mais (percurso maior, destino mudado, gorjeta, taxa de cancelamento) " +
                        "fica em \"Ganho extra\", um lançamento por dia atualizado sozinho."
                    else "Desligado: a diferença para o \"Hoje\" da Uber não é lançada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = { on = it; OverlayPrefs.setUberExtraOn(ctx, it) })
        }
    }
}

/** Quantos meses fechados aparecem embaixo do Mês (o histórico de jornadas guarda até 400). */
private const val CLOSED_MONTHS = 12

/**
 * Mês fechado: card menor (mês, bruto, corridas, km e líquido); toque abre os números e cada custo
 * com o valor tirado do bruto — "Combustível (23%)".
 */
@Composable
private fun ClosedMonthCard(p: Period, t: PeriodTotals, zone: ZoneId) {
    val costs = remember(t) { CostSplit.breakdown(t) }
    val netPct = CostSplit.percentOf(t.net, t.gross)
    ClosedPeriodCard(
        key = p.from,
        title = ReportMath.monthTitle(p.from, zone),
        value = Format.brl(t.gross),
        summary = "${Format.day(p.from, zone)} a ${Format.day(p.to, zone)} · ${t.rides} corrida${if (t.rides == 1) "" else "s"} · " +
            "${Format.km(t.km)} · líquido ${Format.withPercent(Format.brl(t.net), netPct)}",
    ) {
        StatRow(
            Triple("Bruto", Format.brl(t.gross), Color.Unspecified),
            Triple("Custos", Format.brl(t.cost), RedSignal),
            Triple("Líquido", Format.brl(t.net), profitColor(t.net)),
        )
        StatRow(
            Triple("Corridas", t.rides.toString(), Color.Unspecified),
            Triple("Tempo", ShiftMath.duration(t.hours), Color.Unspecified),
            Triple("Km rodados", Format.km(t.km), Color.Unspecified),
        )
        if (t.km > 0) {
            Text(
                "${Format.perKm(t.perKm)} · ${Format.perHour(t.perHour)} bruto · " +
                    "${Format.perKm(t.netPerKm)} · ${Format.perHour(t.netPerHour)} líquido",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        CostBreakdownBlock(costs, t.km)
    }
}

/** "Hoje", "Ontem, 12/11" ou "Qua, 11/11". */
private fun dayTitle(date: LocalDate, today: LocalDate): String {
    val dm = "%02d/%02d".format(date.dayOfMonth, date.monthValue)
    return when (date) {
        today -> "Hoje"
        today.minusDays(1) -> "Ontem, $dm"
        else -> "${Format.WEEKDAYS[date.dayOfWeek.value - 1]}, $dm"
    }
}

/**
 * Previsão da meta no ritmo atual, para o card do período ([period] = "o mês" / "a semana").
 * null = meta já batida (o card já diz).
 */
private fun forecastText(f: GoalForecast, today: LocalDate, period: String): String? = when (f) {
    GoalForecast.Reached -> null
    GoalForecast.NoPace -> "Ainda sem lucro neste período para calcular o ritmo."
    is GoalForecast.ReachOn -> {
        val pace = "No ritmo atual (lucro médio de ${Format.brl(f.perDay)}/dia)"
        when (f.date) {
            today -> "📈 $pace, você bate a meta hoje."
            today.plusDays(1) -> "📈 $pace, você bate a meta amanhã (${shortDate(f.date)})."
            else -> "📈 $pace, você bate a meta no dia ${shortDate(f.date)}."
        }
    }
    is GoalForecast.Short -> buildString {
        append("📉 No ritmo atual (lucro médio de ${Format.brl(f.perDay)}/dia), não bate: fecha $period com ")
        append("~${Format.brl(f.projected)}, faltando ${Format.brl(f.missing)}.")
        val need = f.neededPerDay
        if (need != null) {
            append(
                if (f.daysLeft == 1) " Para bater, precisa de ${Format.brl(need)} hoje."
                else " Para bater, precisa de ${Format.brl(need)} por dia nos ${f.daysLeft} dias que restam (contando hoje)."
            )
        }
    }
}

/** "22/11 (dom)". */
private fun shortDate(date: LocalDate): String =
    "%02d/%02d (%s)".format(date.dayOfMonth, date.monthValue, Format.WEEKDAYS[date.dayOfWeek.value - 1].lowercase())

/**
 * Resumo de um período: bruto, custos e líquido sempre à vista, corridas e tempo embaixo. O botão
 * Bruto/Líquido troca o R$/km e R$/h entre bruto e líquido e mostra de onde vêm os custos
 * (carro: km × custo/km do veículo; mais os gastos lançados).
 */
@Composable
private fun PeriodCard(
    title: String,
    subtitle: String?,
    totals: PeriodTotals,
    goal: GoalProgress?,
    goalHint: Boolean,
    forecast: String? = null,
    noGoalText: String = "Sem meta definida (toque na bandeira).",
    titleActions: @Composable RowScope.() -> Unit = {},
    footer: @Composable () -> Unit = {},
    advance: AdvanceDay? = null,
) {
    var net by rememberSaveable { mutableStateOf(false) }
    val t = totals
    // Lucro do dia com o adiantamento (meta batida): o adiantado sai, o que pagou custos entra.
    val netValue = GoalAdvance.net(t.net, advance)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                titleActions()
                Spacer(Modifier.weight(1f))
                NetToggle(net, onToggle = { net = !net })
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // Bruto, custos e líquido sempre à vista; corridas e tempo na linha de baixo (mesmas colunas).
            Row(Modifier.fillMaxWidth()) {
                LabeledValue("Ganho bruto", Format.brl(t.gross), Modifier.weight(1f))
                LabeledValue("Custos", Format.brl(t.cost), Modifier.weight(1f), color = RedSignal)
                LabeledValue(
                    "Líquido",
                    Format.brl(netValue),
                    Modifier.weight(1f),
                    color = if (netValue < 0) RedSignal else GreenSignal,
                )
            }
            Row(Modifier.fillMaxWidth()) {
                LabeledValue("Corridas", t.rides.toString(), Modifier.weight(1f))
                LabeledValue("Tempo", ShiftMath.duration(t.hours), Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            if (t.km > 0) {
                Text(
                    if (net) {
                        "${Format.km(t.km)} · ${Format.perKm(t.netPerKm)} · ${Format.perHour(t.netPerHour)} líquidos"
                    } else {
                        "${Format.km(t.km)} · ${Format.perKm(t.perKm)} · ${Format.perHour(t.perHour)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (net) {
                Text(
                    "Custos: desgaste ${Format.brl(t.kmCost)} (${Format.km(t.km)} × ${Format.perKm(t.kmCostPerKm)})" +
                        fixedCostText(t) + " + gastos lançados ${Format.brl(t.expenses)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (t.manualIncome > 0) {
                Text(
                    "Bruto = corridas ${Format.brl(t.ridesGross)} + ganhos lançados ${Format.brl(t.manualIncome)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (t.pendingRides > 0) {
                Text(
                    "🚕 ${t.pendingRides} em andamento · soma no ganho ao finalizar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (advance != null && !advance.isEmpty) AdvanceLines(advance)
            if (goal != null) {
                LinearProgressIndicator(progress = { goal.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (goal.reached) {
                        "Meta de lucro de ${Format.brl(goal.goal)} batida! (${goal.percent}%)"
                    } else {
                        "Meta de lucro ${Format.brl(goal.goal)} · ${goal.percent}% · faltam ${Format.brl(goal.remaining)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                forecast?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            } else if (goalHint) {
                Text(noGoalText, style = MaterialTheme.typography.bodySmall)
            }
            footer()
        }
    }
}

/**
 * Adiantamento para as despesas no dia (meta batida): quanto das corridas foi adiantado, quanto dos
 * custos do dia ele pagou e o saldo que fica para os próximos dias.
 */
@Composable
private fun AdvanceLines(a: AdvanceDay) {
    val style = MaterialTheme.typography.bodySmall
    if (a.diverted >= 0.01) {
        Text("🛡️ Meta batida: ${Format.brl(a.diverted)} das corridas adiantados para as despesas dos próximos dias", style = style)
    }
    if (a.available >= 0.01) {
        Text("💳 Custos do dia pagos pelo adiantamento: ${Format.brl(a.used)} de ${Format.brl(a.available)}", style = style)
    }
    Text(
        "Líquido = bruto − custos − adiantado + pago pelo adiantamento · saldo para os próximos dias ${Format.brl(a.left)}",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Calendário para escolher o dia da seção "Hoje" (até hoje). */
@Composable
private fun DayPickerDialog(selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // O DatePicker trabalha com meia-noite UTC de cada data.
    val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L))) }
                onDismiss()
            }) { Text("Ver dia") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun EntryRow(entry: FinanceEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val income = entry.kind == EntryKind.INCOME
    val details = buildList {
        add(Format.dayTime(entry.atMillis))
        entry.km?.let { add(Format.km(it)) }
        entry.note?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                when {
                    // Lançado sozinho pela diferença para o "Hoje" da Uber (core/earnings/UberExtra).
                    UberExtra.isExtra(entry) -> "Ganho extra · ${entry.platform?.label.orEmpty()} (automático)"
                    income -> "Ganho · ${entry.category}"
                    else -> "Gasto · ${entry.category}"
                },
            )
        },
        supportingContent = { Text(details) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (income) "+" else "−") + Format.brl(entry.amount),
                    color = if (income) GreenSignal else RedSignal,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Excluir") }
            }
        },
    )
}

@Composable
private fun EntryDialog(initial: FinanceEntry?, onDismiss: () -> Unit, onSave: (FinanceEntry) -> Unit) {
    var kind by remember { mutableStateOf(initial?.kind ?: EntryKind.INCOME) }
    var platform by remember { mutableStateOf(if (initial == null) Platform.UBER else initial.platform) }
    var incomeCategory by remember {
        mutableStateOf(initial?.takeIf { it.kind == EntryKind.INCOME }?.category ?: Platform.UBER.label)
    }
    var expenseCategory by remember {
        mutableStateOf(initial?.takeIf { it.kind == EntryKind.EXPENSE }?.category ?: EXPENSE_CATEGORIES.first())
    }
    var amount by remember { mutableStateOf(Format.input(initial?.amount)) }
    var km by remember { mutableStateOf(Format.input(initial?.km)) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    val amountValue = Format.parse(amount)
    val valid = amountValue != null && amountValue > 0 && (kind == EntryKind.EXPENSE || isBlankOrNumber(km))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Novo lançamento" else "Editar lançamento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == EntryKind.INCOME, onClick = { kind = EntryKind.INCOME }, label = { Text("Ganho") })
                    FilterChip(selected = kind == EntryKind.EXPENSE, onClick = { kind = EntryKind.EXPENSE }, label = { Text("Gasto") })
                }
                if (kind == EntryKind.INCOME) {
                    Text("Plataforma", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Platform.entries.forEach { p ->
                            FilterChip(
                                selected = platform == p,
                                onClick = { platform = p; incomeCategory = p.label },
                                label = { Text(p.label) },
                            )
                        }
                        FilterChip(
                            selected = platform == null,
                            onClick = { platform = null; incomeCategory = OTHER_PLATFORM },
                            label = { Text(OTHER_PLATFORM) },
                        )
                    }
                } else {
                    Text("Categoria", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EXPENSE_CATEGORIES.forEach { c ->
                            FilterChip(selected = expenseCategory == c, onClick = { expenseCategory = c }, label = { Text(c) })
                        }
                    }
                }
                MoneyField("Valor", amount, { amount = it })
                if (kind == EntryKind.INCOME) {
                    NumberField("Km rodados (opcional)", km, { km = it }, suffix = "km")
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Observação (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val income = kind == EntryKind.INCOME
                    onSave(
                        FinanceEntry(
                            id = initial?.id ?: 0,
                            kind = kind,
                            category = if (income) incomeCategory else expenseCategory,
                            amount = amountValue ?: 0.0,
                            platform = if (income) platform else null,
                            km = if (income) Format.parse(km)?.takeIf { it > 0 } else null,
                            note = note.trim().ifEmpty { null },
                            atMillis = initial?.atMillis ?: System.currentTimeMillis(),
                        )
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun GoalsDialog(
    goals: Goals,
    month: YearMonth,
    monthLabel: String,
    onDismiss: () -> Unit,
    onSave: (Goals) -> Unit,
) {
    var daily by remember { mutableStateOf(Format.input(goals.daily)) }
    var monthly by remember { mutableStateOf(Format.input(goals.monthly)) }
    var byWeekday by remember { mutableStateOf(goals.byWeekday) }
    var selected by remember { mutableStateOf(emptySet<DayOfWeek>()) }
    var dayValue by remember { mutableStateOf("") }
    val valid = isBlankOrNumber(daily) && isBlankOrNumber(monthly)
    // A lista da semana já mostra a meta geral que está sendo digitada agora.
    val preview = Goals(daily = Format.parse(daily)?.takeIf { it > 0 }, byWeekday = byWeekday)
    val dayGoal = Format.parse(dayValue)?.takeIf { it > 0 }
    // A meta mensal que está sendo digitada, dividida pelas semanas do mês (pesando as metas dos dias).
    val monthlyPreview = preview.copy(monthly = Format.parse(monthly)?.takeIf { it > 0 })
    val slices = remember(monthlyPreview, month) { GoalPlan.monthWeeks(monthlyPreview, month) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metas de lucro") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "As metas são do lucro líquido: o que sobra depois de descontar o custo do carro " +
                        "(desgaste por km e custos fixos em % do valor das corridas, de Custos fixos) e os gastos lançados.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                MoneyField("Meta geral do dia", daily, { daily = it })
                Text(
                    "Vale nos dias sem meta própria. Deixe em branco para não usar meta.",
                    style = MaterialTheme.typography.bodySmall,
                )
                MoneyField("Meta mensal", monthly, { monthly = it })
                if (slices.isNotEmpty()) {
                    Text(
                        "Por semana em $monthLabel (seg a dom, como a Uber):",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    slices.forEach { w ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (w.from == w.to) shortDate(w.from) else "${shortDate(w.from)} a ${shortDate(w.to)}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                Format.brl(w.amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MappColors.Teal,
                            )
                        }
                    }
                    Text(
                        (if (GoalPlan.hasDayGoals(monthlyPreview)) {
                            "Dividida pelo peso da meta de cada dia (dia de meta maior leva mais). "
                        } else {
                            "Dividida igual pelos dias do mês. "
                        }) + "Semana que vira o mês soma a parte de cada mês. A meta de cada dia (seção Hoje " +
                            "e anel do ícone M) também sai da meta mensal, do mesmo jeito; as metas por dia " +
                            "abaixo viram o peso de cada dia.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SectionTitle("Meta por dia da semana")
                Text(
                    "Toque nos dias, digite o valor e Aplicar. Dá para marcar vários de uma vez.",
                    style = MaterialTheme.typography.bodySmall,
                )
                WeekGoals.DAYS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { d ->
                            FilterChip(
                                selected = d in selected,
                                onClick = { selected = if (d in selected) selected - d else selected + d },
                                label = { Text(WeekGoals.short(d)) },
                            )
                        }
                    }
                }
                MoneyField(
                    "Meta destes dias",
                    dayValue,
                    { dayValue = it },
                    Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = selected.isNotEmpty() && dayGoal != null,
                        onClick = {
                            byWeekday = WeekGoals.withDays(preview, selected, dayGoal).byWeekday
                            selected = emptySet()
                            dayValue = ""
                        },
                    ) { Text("Aplicar") }
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            byWeekday = WeekGoals.withDays(preview, selected, null).byWeekday
                            selected = emptySet()
                            dayValue = ""
                        },
                    ) { Text("Usar a geral") }
                }
                WeekGoals.groups(preview).forEach { g ->
                    val own = WeekGoals.own(preview, g.days.first()) != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = g.days.toSet()
                                dayValue = Format.input(g.goal)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (g.goal == null) "sem meta" else Format.brl(g.goal) + if (own) "" else " (geral)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (own) MappColors.Teal else MappColors.TextDim,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    Goals(
                        daily = Format.parse(daily)?.takeIf { it > 0 },
                        monthly = Format.parse(monthly)?.takeIf { it > 0 },
                        byWeekday = byWeekday,
                    )
                )
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** " + fixos R$ 45,00 (25,0% do valor das corridas)" — vazio sem custos fixos no período. */
internal fun fixedCostText(t: PeriodTotals): String {
    val share = t.fixedShare ?: return ""
    return " + fixos ${Format.brl(t.fixedCost)} (${Format.share(share)} do valor das corridas)"
}
