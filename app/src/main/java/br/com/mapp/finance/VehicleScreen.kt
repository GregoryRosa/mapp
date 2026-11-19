@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.mapp.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.CostUnit
import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.model.KmBase
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.KmAverage
import br.com.mapp.core.shift.KmAverages
import br.com.mapp.overlay.ShiftTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToLong

/** Linha em edição de um custo extra. [key] só identifica a linha na tela. */
private data class ExtraRow(val key: Int, val name: String, val value: String, val unit: CostUnit)

/**
 * Salvamento fora da tela: termina mesmo se o motorista voltar logo depois de digitar (o escopo da
 * tela seria cancelado ao sair).
 */
private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Espera depois da última tecla antes de salvar sozinho. */
private const val AUTOSAVE_MS = 600L

/** Aba Custos fixos da tela Financeiro (combustível, manutenção e outros custos por km). */
@Composable
internal fun FixedCostsTab() {
    val repos = MappApp.repos
    val ctx = LocalContext.current.applicationContext
    val saved by remember { repos.vehicle.config() }
        .collectAsStateWithLifecycle<VehicleCostConfig?>(initialValue = null)
    // Valores de quando a tela abriu: o formulário não é recarregado a cada salvamento automático.
    val opened = remember(saved != null) { saved }

    val initial = opened
    val current = saved
    if (initial == null || current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        VehicleForm(
            initial = initial,
            saved = current,
            modifier = Modifier,
            onSave = { cfg ->
                saveScope.launch {
                    repos.vehicle.save(cfg)
                    // Jornadas de hoje já encerradas também passam a usar o custo/km novo.
                    ShiftTracker.applyCostToday(ctx, cfg.costPerKm, CostSplit.parts(cfg))
                }
            },
        )
    }
}

/**
 * Base de km automática (média das jornadas) atualizada fora da tela: chamada quando uma jornada é
 * encerrada. Com custo digitado pelo mês, o R$/km muda junto e as jornadas de hoje passam a usá-lo.
 */
object FixedCostsSync {
    fun afterShiftEnded(ctx: android.content.Context) {
        val app = ctx.applicationContext
        saveScope.launch {
            runCatching {
                val repos = MappApp.repos
                val cfg = repos.vehicle.config().first()
                if (!cfg.kmBase.auto) return@launch
                val avg = KmAverages.of(ShiftTracker.history(app).value, System.currentTimeMillis(), ZoneId.systemDefault())
                val base = KmAverages.applied(cfg.kmBase, avg)
                if (base == cfg.kmBase) return@launch
                val next = cfg.copy(kmBase = base)
                repos.vehicle.save(next)
                if (next.costPerKm != cfg.costPerKm) {
                    ShiftTracker.applyCostToday(app, next.costPerKm, CostSplit.parts(next))
                }
            }
        }
    }
}

@Composable
private fun VehicleForm(
    initial: VehicleCostConfig,
    saved: VehicleCostConfig,
    modifier: Modifier,
    onSave: (VehicleCostConfig) -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    var fuelType by remember(initial) { mutableStateOf(initial.fuelType) }
    var fuel by remember(initial) { mutableStateOf(Format.input(initial.fuelPricePerUnit)) }
    var kmPerLiter by remember(initial) { mutableStateOf(Format.input(initial.kmPerUnit)) }

    // Trocar o tipo preenche preço/consumo típicos; voltar ao tipo salvo restaura os valores salvos.
    fun selectType(type: FuelType) {
        if (type == fuelType) return
        fuelType = type
        val price = if (type == initial.fuelType) initial.fuelPricePerUnit else type.suggestedPrice
        val km = if (type == initial.fuelType) initial.kmPerUnit else type.suggestedKmPerUnit
        fuel = Format.input(price)
        kmPerLiter = Format.input(km)
    }
    var maintenance by remember(initial) { mutableStateOf(costText(initial.maintenance, initial.maintenanceUnit)) }
    var maintenanceUnit by remember(initial) { mutableStateOf(initial.maintenanceUnit) }
    var depreciation by remember(initial) { mutableStateOf(costText(initial.depreciation, initial.depreciationUnit)) }
    var depreciationUnit by remember(initial) { mutableStateOf(initial.depreciationUnit) }
    val extras = remember(initial) {
        mutableStateListOf<ExtraRow>().apply {
            initial.extraCosts.forEachIndexed { i, c -> add(ExtraRow(i, c.name, costText(c.amount, c.unit), c.unit)) }
        }
    }
    var nextKey by remember(initial) { mutableStateOf(initial.extraCosts.size) }
    // Diálogo do nome: null = fechado, NEW_COST = custo novo, outro = key da linha a renomear.
    var naming by remember { mutableStateOf<Int?>(null) }
    naming?.let { target ->
        val index = extras.indexOfFirst { it.key == target }
        CostNameDialog(
            title = if (target == NEW_COST) "Novo custo" else "Renomear custo",
            confirm = if (target == NEW_COST) "Adicionar" else "Salvar",
            initial = extras.getOrNull(index)?.name.orEmpty(),
            onConfirm = { name ->
                if (target == NEW_COST) {
                    extras.add(ExtraRow(nextKey++, name, "", CostUnit.PER_MONTH))
                } else if (index >= 0) {
                    extras[index] = extras[index].copy(name = name)
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    // Base dos custos do mês: média das jornadas encerradas (atualizada sozinha) ou digitada.
    var baseAuto by remember(initial) { mutableStateOf(initial.kmBase.auto) }
    var kmPerDay by remember(initial) { mutableStateOf(Format.input(initial.kmBase.kmPerDay)) }
    var daysPerMonth by remember(initial) { mutableStateOf(Format.input(initial.kmBase.daysPerMonth)) }
    val history by remember { ShiftTracker.history(ctx) }.collectAsStateWithLifecycle()
    val average = remember(history) { KmAverages.of(history, System.currentTimeMillis(), ZoneId.systemDefault()) }
    val needKm = !(baseAuto && average != null)
    val needDays = !(baseAuto && average?.daysPerMonth != null)
    val typedKm = Format.parse(kmPerDay)?.takeIf { it > 0 }
    val typedDays = Format.parse(daysPerMonth)?.takeIf { it >= 1 && it <= 31 }
    val base: KmBase? = if ((needKm && typedKm == null) || (needDays && typedDays == null)) {
        null
    } else {
        KmAverages.applied(
            KmBase(typedKm ?: initial.kmBase.kmPerDay, typedDays ?: initial.kmBase.daysPerMonth, baseAuto),
            average,
        )
    }
    // Para as contas na tela enquanto a base está sendo corrigida.
    val shownBase = base ?: saved.kmBase

    /** Troca a unidade mantendo o custo: R$/km × km do mês = R$/mês (e o contrário). */
    fun convert(text: String, from: CostUnit, to: CostUnit): String {
        val v = Format.parse(text) ?: return text
        if (from == to || shownBase.monthKm <= 0) return text
        return when (to) {
            CostUnit.PER_MONTH -> Format.input((v * shownBase.monthKm * 100).roundToLong() / 100.0)
            CostUnit.PER_KM -> Format.inputPrecise(v / shownBase.monthKm)
        }
    }

    // Linha toda vazia é ignorada; sem nome vira "Outro custo"; valor inválido bloqueia o Salvar.
    val extraCosts: List<ExtraKmCost>? = extras
        .filterNot { it.name.isBlank() && it.value.isBlank() }
        .map { row ->
            val v = Format.parse(row.value)?.takeIf { it >= 0 } ?: return@map null
            ExtraKmCost(row.name.trim().ifEmpty { CostSplit.EXTRA }, v, row.unit)
        }
        .let { list -> if (list.any { it == null }) null else list.filterNotNull() }

    val fuelV = Format.parse(fuel)
    val kmlV = Format.parse(kmPerLiter)
    val maintV = Format.parse(maintenance)
    val depV = Format.parse(depreciation)
    val config = if (fuelV != null && kmlV != null && kmlV > 0 && maintV != null && depV != null &&
        fuelV >= 0 && maintV >= 0 && depV >= 0 && extraCosts != null && base != null
    ) {
        VehicleCostConfig(
            fuelType = fuelType,
            fuelPricePerUnit = fuelV,
            kmPerUnit = kmlV,
            maintenance = maintV,
            depreciation = depV,
            extraCosts = extraCosts,
            maintenanceUnit = maintenanceUnit,
            depreciationUnit = depreciationUnit,
            kmBase = base,
        )
    } else {
        null
    }

    // Salva sozinho a cada mudança válida: vale na hora para as ofertas, a jornada e os totais do dia.
    LaunchedEffect(config) {
        if (config == null || config == saved) return@LaunchedEffect
        delay(AUTOSAVE_MS)
        onSave(config)
    }
    // Saiu da tela antes do salvamento automático: salva assim mesmo.
    val unsaved by rememberUpdatedState(config?.takeIf { it != saved })
    DisposableEffect(Unit) { onDispose { unsaved?.let(onSave) } }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Combustível", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelType.entries.forEach { t ->
                FilterChip(selected = fuelType == t, onClick = { selectType(t) }, label = { Text(t.label) })
            }
        }
        if (fuelType.isElectric) {
            NumberField("Preço da energia (recarga)", fuel, { fuel = it }, suffix = fuelType.priceSuffix)
            NumberField("Consumo", kmPerLiter, { kmPerLiter = it }, suffix = fuelType.consumptionSuffix)
            Text(
                "Use o preço do kWh da sua recarga (conta de luz em casa ou eletroposto) e a eficiência do carro em km/kWh.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            NumberField("Preço do ${fuelType.label.lowercase()}", fuel, { fuel = it }, suffix = fuelType.priceSuffix)
            NumberField("Consumo", kmPerLiter, { kmPerLiter = it }, suffix = fuelType.consumptionSuffix)
        }
        CostInput(
            "Manutenção", maintenance, { maintenance = it }, maintenanceUnit,
            { u -> maintenance = convert(maintenance, maintenanceUnit, u); maintenanceUnit = u }, shownBase,
        )
        CostInput(
            "Depreciação", depreciation, { depreciation = it }, depreciationUnit,
            { u -> depreciation = convert(depreciation, depreciationUnit, u); depreciationUnit = u }, shownBase,
        )

        Text("Outros custos", style = MaterialTheme.typography.labelLarge)
        // O nome fica no rótulo do campo, como Manutenção e Depreciação; ⋮ renomeia ou remove.
        extras.forEachIndexed { i, row ->
            key(row.key) {
                CostInput(
                    row.name.trim().ifEmpty { CostSplit.EXTRA }, row.value, { extras[i] = row.copy(value = it) }, row.unit,
                    { u -> extras[i] = row.copy(value = convert(row.value, row.unit, u), unit = u) }, shownBase,
                    trailingIcon = {
                        ExtraCostMenu(
                            onRename = { naming = row.key },
                            onRemove = { extras.removeAt(i) },
                        )
                    },
                )
            }
        }
        OutlinedButton(
            // Pede o nome antes; o custo novo já vem pelo total do mês (seguro, IPVA, aluguel...).
            onClick = { naming = NEW_COST },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Adicionar custo", Modifier.padding(start = 8.dp))
        }
        if (extras.isEmpty()) {
            Text(
                "Ex.: seguro, IPVA, aluguel ou parcela do carro, pneus — coloque o total do mês e o MApp divide " +
                    "pelos km que você roda no mês.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        KmBaseCard(
            auto = baseAuto,
            onAuto = { auto ->
                if (!auto && baseAuto) {
                    // Passa a digitar a partir do que estava valendo.
                    kmPerDay = Format.input(shownBase.kmPerDay)
                    daysPerMonth = Format.input(shownBase.daysPerMonth)
                }
                baseAuto = auto
            },
            kmPerDay = kmPerDay,
            onKmPerDay = { kmPerDay = it },
            daysPerMonth = daysPerMonth,
            onDaysPerMonth = { daysPerMonth = it },
            average = average,
            base = base,
            needKm = needKm,
            needDays = needDays,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Custo por km", style = MaterialTheme.typography.labelLarge)
                if (config == null) {
                    Text(
                        "Preencha todos os campos (consumo, km por dia e dias no mês maiores que zero).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        Format.costPerKm(config.costPerKm),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${if (fuelType.isElectric) "Energia" else fuelType.label} ${Format.costPerKm(config.energyCostPerKm)} + " +
                            "manutenção ${Format.costPerKm(config.maintenancePerKm)} + " +
                            "depreciação ${Format.costPerKm(config.depreciationPerKm)}" +
                            config.extraCosts.joinToString("") { " + ${it.name} ${Format.costPerKm(config.perKmOf(it))}" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (config.usesMonthly) {
                        val monthTotal = listOf(
                            config.maintenance to config.maintenanceUnit,
                            config.depreciation to config.depreciationUnit,
                        ).plus(config.extraCosts.map { it.amount to it.unit })
                            .filter { it.second == CostUnit.PER_MONTH }
                            .sumOf { it.first }
                        Text(
                            "Custos do mês: ${Format.brl(monthTotal)} ÷ ${Format.kmWhole(config.kmBase.monthKm)} = " +
                                Format.costPerKm(config.kmBase.perKm(monthTotal, CostUnit.PER_MONTH)) + ".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Ex.: corrida de 10 km custa ${Format.brl(config.costPerKm * 10)} · 100 km rodados custam " +
                            Format.brl(config.costPerKm * 100) + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text(
            when {
                config == null -> "⚠️ Corrija os campos para salvar."
                config == saved -> "✓ Salvo — já vale para as ofertas, a jornada e os totais do dia."
                else -> "Salvando…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (config == null) RedSignal else GreenSignal,
        )
        Text(
            "O custo por km é usado no lucro líquido de cada oferta, no custo da jornada e no valor líquido " +
                "de Ganhos e gastos. Jornadas de dias anteriores ficam com o custo/km daquele dia.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Texto do campo: R$/km com até 3 casas, R$/mês com 2. */
private fun costText(amount: Double, unit: CostUnit): String =
    if (unit == CostUnit.PER_KM) Format.inputPrecise(amount) else Format.input(amount)

/** Campo de custo com a escolha mês/km e, embaixo, o equivalente (R$/km do custo do mês). */
@Composable
private fun CostInput(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    unit: CostUnit,
    onUnit: (CostUnit) -> Unit,
    base: KmBase,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField(label, value, onValue, Modifier.weight(1f), suffix = unit.suffix, trailingIcon = trailingIcon)
            FilterChip(
                selected = unit == CostUnit.PER_MONTH,
                onClick = { onUnit(CostUnit.PER_MONTH) },
                label = { Text("mês") },
            )
            FilterChip(
                selected = unit == CostUnit.PER_KM,
                onClick = { onUnit(CostUnit.PER_KM) },
                label = { Text("km") },
            )
        }
        val amount = Format.parse(value)?.takeIf { it >= 0 }
        val equivalent = when {
            amount == null -> null
            unit == CostUnit.PER_MONTH && base.monthKm <= 0 -> "Informe os km do mês abaixo para calcular o R$/km."
            unit == CostUnit.PER_MONTH -> "= ${Format.costPerKm(base.perKm(amount, unit))}"
            base.monthKm > 0 -> "≈ ${Format.brl(base.perMonth(amount, unit))} por mês"
            else -> null
        }
        if (equivalent != null) {
            Text(
                equivalent,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (unit == CostUnit.PER_MONTH) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

/** Marca do diálogo de nome para "custo novo" (as linhas usam key ≥ 0). */
private const val NEW_COST = -1

/** ⋮ dentro do campo de um custo extra: renomear ou remover. */
@Composable
private fun ExtraCostMenu(onRename: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Opções do custo")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Renomear") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Remover") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

/** Nome do custo extra (vira o rótulo do campo de valor). */
@Composable
private fun CostNameDialog(
    title: String,
    confirm: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val ok = name.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome") },
                placeholder = { Text("Ex.: seguro, IPVA, financiamento") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(name.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text.trim()) }, enabled = ok) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Km rodados no mês: média das jornadas (padrão, atualizada sozinha) ou digitada. */
@Composable
private fun KmBaseCard(
    auto: Boolean,
    onAuto: (Boolean) -> Unit,
    kmPerDay: String,
    onKmPerDay: (String) -> Unit,
    daysPerMonth: String,
    onDaysPerMonth: (String) -> Unit,
    average: KmAverage?,
    base: KmBase?,
    needKm: Boolean,
    needDays: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Km rodados no mês", style = MaterialTheme.typography.labelLarge)
            Text(
                "É por eles que o MApp divide os custos do mês para achar o R$/km.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = auto, onClick = { onAuto(true) }, label = { Text("Média das jornadas") })
                FilterChip(selected = !auto, onClick = { onAuto(false) }, label = { Text("Digitar") })
            }
            if (auto && average != null) {
                Text(
                    "${Format.kmWhole(average.kmPerDay)} por dia rodado — ${average.workedDays} " +
                        "${if (average.workedDays == 1) "dia" else "dias"} com jornada nos últimos ${average.windowDays} " +
                        "${if (average.windowDays == 1) "dia" else "dias"}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (average.daysPerMonth != null) {
                    Text(
                        "Nesse ritmo: ${average.daysPerMonth.toInt()} dias rodados no mês. Atualiza sozinho ao encerrar cada jornada.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (auto) {
                Text(
                    "Ainda sem jornadas com km nos últimos 30 dias: informe uma estimativa. A média entra sozinha depois.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (needKm || needDays) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (needKm) {
                        NumberField("Km por dia", kmPerDay, onKmPerDay, Modifier.weight(1f), suffix = "km")
                    }
                    if (needDays) {
                        NumberField("Dias rodados no mês", daysPerMonth, onDaysPerMonth, Modifier.weight(1f), suffix = "dias")
                    }
                }
                if (auto && average != null && needDays) {
                    Text(
                        "Com menos de ${KmAverages.MIN_WINDOW_FOR_DAYS} dias de jornadas ainda não dá para saber as folgas: " +
                            "informe quantos dias você roda no mês.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                if (base == null) "Informe km por dia e dias no mês (1 a 31)." else "= ${Format.kmWhole(base.monthKm)} por mês",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
