@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.mapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.card.AddressScope
import br.com.mapp.core.card.CardFilter
import br.com.mapp.core.card.CardFilters
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.card.FilterField
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.finance.ConfirmDeleteDialog
import br.com.mapp.finance.Format
import br.com.mapp.finance.MoneyField
import br.com.mapp.finance.NumberField
import br.com.mapp.finance.isBlankOrNumber
import br.com.mapp.overlay.CardHeaderView
import br.com.mapp.overlay.OverlayPrefs

/**
 * Submenu "Filtros" do menu Card: regras do usuário que dão **cor e etiqueta** ao Card quando a
 * corrida casa (ex.: "Área de risco" = endereço com as palavras centro, vila nova; "Longa" = distância
 * total a partir de 20 km). Lista com liga/desliga e ordem (o de cima vale mais na cor); toque abre o
 * editor: nome, o que o filtro olha (dropdown com as informações do Card), a condição, a cor e se
 * pinta o Card e fala o nome. Salvo em `OverlayPrefs.cardFilters`; regras em `core/card/CardFilters`.
 * O filtro só mostra — nunca aceita nem recusa corrida.
 */
@Composable
internal fun CardFiltersTab() {
    val ctx = LocalContext.current
    var filters by remember { mutableStateOf(OverlayPrefs.cardFilters(ctx)) }
    // Filtro aberto no editor (novo ou existente); null = lista.
    var draft by remember { mutableStateOf<CardFilter?>(null) }
    var sample by remember { mutableIntStateOf(0) }
    val layout = remember { OverlayPrefs.cardLayout(ctx) }

    val profile by remember { MappApp.repos.profiles.active() }.collectAsStateWithLifecycle(FilterProfile())
    val cost by remember { MappApp.repos.vehicle.config() }.collectAsStateWithLifecycle(VehicleCostConfig())
    val offer = remember(sample, profile, cost) { OfferEvaluator.evaluate(CARD_SAMPLES[sample].second, profile, cost) }

    fun save(list: List<CardFilter>) {
        filters = list
        OverlayPrefs.setCardFilters(ctx, list)
    }

    val editing = draft
    if (editing != null) {
        BackHandler { draft = null }
        FilterEditor(
            initial = editing,
            isNew = filters.none { it.id == editing.id },
            offer = offer,
            profile = profile,
            layout = layout,
            onSave = { save(CardFilters.upsert(filters, it)); draft = null },
            onDelete = { save(CardFilters.remove(filters, editing.id)); draft = null },
            onCancel = { draft = null },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            // Prévia fixa no topo: as corridas de exemplo com os filtros salvos.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CardPreview(offer, profile, layout, CardFilters.matching(filters, offer))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CARD_SAMPLES.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = sample == i, onClick = { sample = i }, label = { Text(label) })
                    }
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Quando a corrida casa com um filtro, o Card ganha a cor e a etiqueta dele. " +
                        "Se mais de um casar, vale a cor do que está mais em cima (use as setas). " +
                        "O filtro só mostra: o MApp nunca aceita nem recusa corrida.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (filters.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Nenhum filtro ainda", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Ideias: \"Área de risco\" com as palavras dos bairros que você evita; " +
                                    "\"Aeroporto\" só no destino; \"Longa\" com distância total a partir de 20 km; " +
                                    "\"Busca longe\" com busca a partir de 4 km.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                filters.forEachIndexed { i, f ->
                    FilterRow(
                        filter = f,
                        first = i == 0,
                        last = i == filters.lastIndex,
                        onEdit = { draft = f },
                        onToggle = { on -> save(filters.map { if (it.id == f.id) it.copy(enabled = on) else it }) },
                        onMove = { delta -> save(CardFilters.move(filters, f.id, delta)) },
                    )
                }
                Button(
                    onClick = { draft = CardFilters.create(filters, System.currentTimeMillis()) },
                    enabled = filters.size < CardFilters.MAX_FILTERS,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("＋ Novo filtro") }
                if (filters.size >= CardFilters.MAX_FILTERS) {
                    Text("Limite de ${CardFilters.MAX_FILTERS} filtros.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** O cabeçalho de verdade do Card (largura real), com os filtros [matched] aplicados. */
@Composable
private fun CardPreview(offer: EvaluatedOffer, profile: FilterProfile, layout: CardLayout, matched: List<CardFilter>) {
    val ctx = LocalContext.current
    val header = remember { CardHeaderView(ctx) }
    AndroidView(
        factory = { header.view },
        modifier = Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)),
        update = { header.bind(offer, profile, layout, matched) },
    )
}

/** Um filtro na lista: cor, nome, regra em uma linha, setas de ordem e liga/desliga. Toque abre o editor. */
@Composable
private fun FilterRow(
    filter: CardFilter,
    first: Boolean,
    last: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(filter.color)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(filter.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val extras = listOfNotNull(
                    if (filter.paint) null else "só etiqueta",
                    if (filter.speak) "voz" else null,
                )
                Text(
                    (listOf(CardFilters.summary(filter)) + extras).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!filter.isComplete) {
                    Text(
                        "Incompleto: toque para terminar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column {
                IconButton(onClick = { onMove(-1) }, enabled = !first, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Subir (vale mais na cor)")
                }
                IconButton(onClick = { onMove(1) }, enabled = !last, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descer")
                }
            }
            Switch(checked = filter.enabled, onCheckedChange = onToggle, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * Editor de um filtro, no lugar da lista (Voltar cancela). Prévia no topo com o filtro aplicado e
 * Excluir / Cancelar / Salvar fixos embaixo.
 */
@Composable
private fun FilterEditor(
    initial: CardFilter,
    isNew: Boolean,
    offer: EvaluatedOffer,
    profile: FilterProfile,
    layout: CardLayout,
    onSave: (CardFilter) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var item by remember { mutableStateOf(initial.item) }
    var wordsText by remember { mutableStateOf(initial.words.joinToString(", ")) }
    var scope by remember { mutableStateOf(initial.scope) }
    var minText by remember { mutableStateOf(Format.input(initial.min)) }
    var maxText by remember { mutableStateOf(Format.input(initial.max)) }
    var color by remember { mutableIntStateOf(initial.color) }
    var paint by remember { mutableStateOf(initial.paint) }
    var speak by remember { mutableStateOf(initial.speak) }
    var confirmDelete by remember { mutableStateOf(false) }

    val words = CardFilters.parseWords(wordsText)
    val min = Format.parse(minText)
    val max = Format.parse(maxText)
    val result = initial.copy(
        name = name, item = item, words = words, scope = scope, min = min, max = max,
        color = color, paint = paint, speak = speak,
    )
    val numbersOk = isBlankOrNumber(minText) && isBlankOrNumber(maxText)
    val swapped = !item.isText && min != null && max != null && min > max
    val valid = result.isComplete && (item.isText || numbersOk)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Sempre com o filtro aplicado, para ver a cor e a etiqueta.
            val shown = result.copy(name = result.displayName.ifBlank { "Novo filtro" }, enabled = true)
            CardPreview(offer, profile, layout, listOf(shown))
            Text("Como o Card fica quando o filtro casa.", style = MaterialTheme.typography.bodySmall)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isNew) "Novo filtro" else "Editar filtro", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("Nome (aparece na etiqueta)") },
                placeholder = { Text("Ex.: Área de risco") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ItemDropdown(item) { item = it }

            if (item.isText) {
                OutlinedTextField(
                    value = wordsText,
                    onValueChange = { wordsText = it },
                    label = { Text("Palavras do endereço") },
                    placeholder = { Text("Ex.: centro, aeroporto, vila nova") },
                    supportingText = {
                        Text(
                            "Separe por vírgula. Basta uma aparecer. Vale a palavra inteira; " +
                                "maiúsculas e acentos não fazem diferença.",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (words.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        words.forEach { w ->
                            FilterChip(
                                selected = true,
                                onClick = { wordsText = (words - w).joinToString(", ") },
                                label = { Text("$w  ✕") },
                            )
                        }
                    }
                }
                Text("Onde procurar", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddressScope.entries.forEach { s ->
                        FilterChip(selected = scope == s, onClick = { scope = s }, label = { Text(s.label) })
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (item.isMoney) {
                        // R$: estilo maquininha (1773 → 17,73).
                        MoneyField("De", minText, { minText = it }, Modifier.weight(1f))
                        MoneyField("Até", maxText, { maxText = it }, Modifier.weight(1f))
                    } else {
                        NumberField(
                            "De", minText, { minText = it }, Modifier.weight(1f),
                            suffix = item.unit.ifEmpty { null }, integer = item.decimals == 0,
                        )
                        NumberField(
                            "Até", maxText, { maxText = it }, Modifier.weight(1f),
                            suffix = item.unit.ifEmpty { null }, integer = item.decimals == 0,
                        )
                    }
                }
                Text(
                    when {
                        swapped -> "O \"de\" precisa ser menor ou igual ao \"até\"."
                        min == null && max == null ->
                            "Preencha um lado ou os dois: só \"de\" = a partir de; só \"até\" = no máximo. " +
                                "Compara com o número que aparece no Card."
                        else -> "Casa com: ${CardFilters.summary(result)}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (swapped) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }

            Text("Cor", fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CardFilters.PALETTE.forEach { c ->
                    ColorSwatch(c, selected = c == color) { color = c }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow(
                        "Pintar o Card com a cor",
                        "Desligado: o Card fica com a cor do semáforo e só a etiqueta ganha a cor.",
                        paint,
                    ) { paint = it }
                    ToggleRow(
                        "Falar o nome",
                        "A voz diz o nome do filtro depois da avaliação da corrida (com a voz ligada).",
                        speak,
                    ) { speak = it }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            Button(onClick = { onSave(result) }, enabled = valid) { Text("Salvar") }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Excluir filtro?",
            text = "\"${initial.displayName}\" sai da lista e o Card deixa de usar a cor dele.",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Dropdown "O que o filtro olha": as informações do Card ([FilterField]). */
@Composable
private fun ItemDropdown(selected: FilterField, onSelect: (FilterField) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("O que o filtro olha", fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.label, Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                FilterField.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.label, fontWeight = if (f == selected) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            onSelect(f)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Int) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(Color(color)).border(1.dp, Color(0x66FFFFFF), CircleShape),
    )
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x66FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(
                "✓",
                color = if (CardFilters.darkText(color)) Color(0xFF212121) else Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
