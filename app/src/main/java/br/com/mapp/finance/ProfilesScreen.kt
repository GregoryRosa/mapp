@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.FilterProfile
import kotlinx.coroutines.launch

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

@Composable
private fun ProfileEditorDialog(initial: FilterProfile?, onDismiss: () -> Unit, onSave: (FilterProfile) -> Unit) {
    val base = initial ?: FilterProfile(name = "")
    var name by remember { mutableStateOf(base.name) }
    var minPerKm by remember { mutableStateOf(Format.input(base.minPerKm)) }
    var goodPerKm by remember { mutableStateOf(Format.input(base.goodPerKm)) }
    var minPerHour by remember { mutableStateOf(Format.input(base.minPerHour)) }
    var goodPerHour by remember { mutableStateOf(Format.input(base.goodPerHour)) }
    var minPrice by remember { mutableStateOf(Format.input(base.minPrice)) }
    var maxPickupKm by remember { mutableStateOf(Format.input(base.maxPickupKm)) }
    var maxPickupMin by remember { mutableStateOf(base.maxPickupMin?.toString().orEmpty()) }
    var minRating by remember { mutableStateOf(Format.input(base.minRating)) }

    val numbers = listOf(minPerKm, goodPerKm, minPerHour, goodPerHour, minPrice, maxPickupKm, maxPickupMin, minRating)
    val valid = name.isNotBlank() && numbers.all { isBlankOrNumber(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Novo perfil" else "Editar perfil") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome (ex.: Dia, Noite, Aeroporto)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField("R$/km mínimo", minPerKm, { minPerKm = it })
                NumberField("R$/km bom", goodPerKm, { goodPerKm = it })
                NumberField("R$/h mínimo", minPerHour, { minPerHour = it })
                NumberField("R$/h bom", goodPerHour, { goodPerHour = it })
                NumberField("Valor mínimo da corrida", minPrice, { minPrice = it }, suffix = "R$")
                NumberField("Busca máxima", maxPickupKm, { maxPickupKm = it }, suffix = "km")
                NumberField("Busca máxima", maxPickupMin, { maxPickupMin = it }, suffix = "min", integer = true)
                NumberField("Nota mínima do passageiro", minRating, { minRating = it })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    base.copy(
                        name = name.trim(),
                        minPerKm = Format.parse(minPerKm),
                        goodPerKm = Format.parse(goodPerKm),
                        minPerHour = Format.parse(minPerHour),
                        goodPerHour = Format.parse(goodPerHour),
                        minPrice = Format.parse(minPrice),
                        maxPickupKm = Format.parse(maxPickupKm),
                        maxPickupMin = Format.parse(maxPickupMin)?.toInt(),
                        minRating = Format.parse(minRating),
                    )
                )
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
