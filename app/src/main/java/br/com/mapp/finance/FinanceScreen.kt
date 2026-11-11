@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.Platform
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.Goals
import kotlinx.coroutines.launch

private val EXPENSE_CATEGORIES = listOf("Combustível", "Manutenção", "Alimentação", "Lavagem", "Outros")
private const val OTHER_PLATFORM = "Outros"

@Composable
fun FinanceScreen(onBack: () -> Unit) {
    val repos = MappApp.repos
    val scope = rememberCoroutineScope()
    val month = remember { ReportMath.currentMonth() }
    val today = remember { ReportMath.today() }

    val entries by remember(month) { repos.finance.entries(month.from, month.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val goals by remember { repos.finance.goals() }.collectAsStateWithLifecycle(initialValue = Goals())

    val todaySummary = remember(entries, today) { ReportMath.summarize(entries, today) }
    val monthSummary = remember(entries) { ReportMath.summarize(entries) }

    var showEntryDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FinanceEntry?>(null) }
    var showGoals by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<FinanceEntry?>(null) }

    Scaffold(
        topBar = {
            BackTopBar("Ganhos e gastos", onBack) {
                IconButton(onClick = { showGoals = true }) { Icon(Icons.Filled.Flag, contentDescription = "Metas") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Lançar") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { editing = null; showEntryDialog = true },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryCard("Hoje", todaySummary, ReportMath.goalProgress(todaySummary.income, goals.daily)) }
            item { SummaryCard("Mês", monthSummary, ReportMath.goalProgress(monthSummary.income, goals.monthly)) }
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
            onDismiss = { showGoals = false },
            onSave = { g ->
                scope.launch { repos.finance.saveGoals(g) }
                showGoals = false
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

@Composable
private fun SummaryCard(title: String, s: PeriodSummary, goal: GoalProgress?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                LabeledValue("Ganhos", Format.brl(s.income), Modifier.weight(1f))
                LabeledValue("Gastos", Format.brl(s.expense), Modifier.weight(1f))
                LabeledValue(
                    "Lucro",
                    Format.brl(s.profit),
                    Modifier.weight(1f),
                    color = if (s.profit < 0) RedSignal else GreenSignal,
                )
            }
            if (s.km > 0) {
                Text(
                    "${Format.km(s.km)} · ${Format.perKm(s.incomePerKm)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (goal != null) {
                LinearProgressIndicator(progress = { goal.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (goal.reached) {
                        "Meta de ${Format.brl(goal.goal)} batida! (${goal.percent}%)"
                    } else {
                        "Meta ${Format.brl(goal.goal)} · ${goal.percent}% · faltam ${Format.brl(goal.remaining)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Sem meta definida (toque na bandeira).", style = MaterialTheme.typography.bodySmall)
            }
        }
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
        headlineContent = { Text(if (income) "Ganho · ${entry.category}" else "Gasto · ${entry.category}") },
        supportingContent = { Text(details) },
        trailingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                NumberField("Valor", amount, { amount = it }, suffix = "R$")
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
private fun GoalsDialog(goals: Goals, onDismiss: () -> Unit, onSave: (Goals) -> Unit) {
    var daily by remember { mutableStateOf(Format.input(goals.daily)) }
    var monthly by remember { mutableStateOf(Format.input(goals.monthly)) }
    val valid = isBlankOrNumber(daily) && isBlankOrNumber(monthly)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metas de ganhos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Meta diária", daily, { daily = it }, suffix = "R$")
                NumberField("Meta mensal", monthly, { monthly = it }, suffix = "R$")
                Text("Deixe em branco para não usar meta.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    Goals(
                        daily = Format.parse(daily)?.takeIf { it > 0 },
                        monthly = Format.parse(monthly)?.takeIf { it > 0 },
                    )
                )
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
