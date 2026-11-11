package br.com.mapp.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.mapp.core.model.Signal

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
internal fun BarRow(label: String, value: Double, max: Double, valueText: String, color: Color = MaterialTheme.colorScheme.primary) {
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
        Text(valueText, Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
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
) {
    val invalid = value.isNotBlank() && Format.parse(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        supportingText = if (invalid) { { Text("Número inválido") } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
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
