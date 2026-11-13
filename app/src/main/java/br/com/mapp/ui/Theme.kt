package br.com.mapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paleta do MApp: navy profundo, verde-água e lilás (estilo "vidro" escuro). */
object MappColors {
    val Navy = Color(0xFF07141B)
    val NavyTop = Color(0xFF0A1C24)
    val DeepTeal = Color(0xFF0E3038)
    val DeepPurple = Color(0xFF22163A)
    val Teal = Color(0xFF5EE6D8)
    val Aqua = Color(0xFF7FD8EA)
    val Lilac = Color(0xFFB59CFF)
    val Periwinkle = Color(0xFF9DB2F0)
    val Text = Color(0xFFE6F2F4)
    val TextDim = Color(0xFF9FB8BF)
    val Glass = Color(0x2E5EE6D8)
    val GlassDark = Color(0x8C0B1A21)
    val Border = Color(0x4D7FD8EA)
    val Profit = Color(0xFF7CF0A8)
    val Loss = Color(0xFFFF8A94)
    val Warn = Color(0xFFFFC56B)

    /** Fundo de todas as telas: brilho verde-água no alto e lilás embaixo. */
    val backgroundBrush = Brush.linearGradient(
        0f to NavyTop,
        0.45f to Navy,
        1f to DeepPurple,
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )

    /** Gradiente da marca (logo, botão principal). */
    val brand = listOf(Teal, Lilac)
    val buttonBrush = Brush.horizontalGradient(listOf(Periwinkle, Teal))
}

private val MappScheme = darkColorScheme(
    primary = MappColors.Teal,
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF124A4A),
    onPrimaryContainer = Color(0xFFBFF6EF),
    secondary = MappColors.Lilac,
    onSecondary = Color(0xFF1E0F3D),
    secondaryContainer = Color(0xFF3A2A66),
    onSecondaryContainer = Color(0xFFE6DDFF),
    tertiary = MappColors.Periwinkle,
    onTertiary = Color(0xFF0F1D3D),
    background = Color.Transparent, // o gradiente fica por trás de todas as telas
    onBackground = MappColors.Text,
    surface = MappColors.NavyTop,
    onSurface = MappColors.Text,
    surfaceVariant = Color(0xFF1A3440),
    onSurfaceVariant = MappColors.TextDim,
    surfaceContainerLowest = Color(0xFF0B1A21),
    surfaceContainerLow = Color(0xFF10232B),
    surfaceContainer = Color(0xFF132A33),
    surfaceContainerHigh = Color(0xFF17303A),
    surfaceContainerHighest = Color(0xFF1A3540),
    outline = Color(0xFF3C5E68),
    outlineVariant = Color(0xFF24424C),
    error = MappColors.Loss,
    onError = Color(0xFF3D0008),
)

private val MappShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val MappTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun MappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MappScheme,
        shapes = MappShapes,
        typography = MappTypography,
        content = content,
    )
}

/**
 * Logo do MApp: um "M" desenhado como rota (linha contínua) com ponto de partida e pin de chegada,
 * em gradiente verde-água → lilás sobre um círculo escuro. Mesmo desenho do ícone flutuante e do
 * ícone do celular (grade 24×24).
 */
@Composable
fun MappLogo(size: Dp, modifier: Modifier = Modifier, withBackground: Boolean = true) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        if (withBackground) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF16404A), MappColors.Navy),
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.3f),
                    radius = this.size.minDimension * 0.75f,
                ),
            )
            drawCircle(
                Brush.linearGradient(MappColors.brand),
                radius = this.size.minDimension / 2 - u * 0.5f,
                style = Stroke(width = u * 1f),
            )
        }
        val brush = Brush.linearGradient(MappColors.brand, start = Offset(5f * u, 7f * u), end = Offset(19f * u, 18f * u))
        val path = Path().apply {
            moveTo(6.5f * u, 17f * u)
            lineTo(6.5f * u, 7.5f * u)
            lineTo(12f * u, 13.5f * u)
            lineTo(17.5f * u, 7.5f * u)
            lineTo(17.5f * u, 15f * u)
        }
        drawPath(path, brush, style = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // partida (anel) e chegada (ponto cheio)
        drawCircle(MappColors.Teal, radius = 1.6f * u, center = Offset(6.5f * u, 17.6f * u), style = Stroke(width = 1.1f * u))
        drawCircle(MappColors.Lilac, radius = 1.9f * u, center = Offset(17.5f * u, 17.2f * u))
    }
}

/** Cartão de "vidro" com borda clara, como os blocos do print de referência. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val bg = if (glow) {
        Brush.linearGradient(listOf(Color(0x3D5EE6D8), Color(0x26B59CFF)))
    } else {
        Brush.linearGradient(listOf(Color(0x241F4A55), Color(0x1A2A2050)))
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MappColors.GlassDark)
            .background(bg)
            .border(1.dp, Brush.linearGradient(listOf(MappColors.Border, Color(0x22B59CFF))), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        content = content,
    )
}

/** Botão principal com gradiente (como o "Get Started" da referência). */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, trailing: String = "→", onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(MappColors.buttonBrush)
            .border(1.dp, Color(0x66FFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color(0xFF0B1F2A), style = MaterialTheme.typography.titleMedium)
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(trailing, color = Color(0xFF0B1F2A), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Bloco pequeno de estatística: rótulo em cima, número grande embaixo. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MappColors.Text) {
    GlassCard(modifier, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MappColors.TextDim)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = valueColor),
            maxLines = 1,
        )
    }
}

/** Título de seção discreto. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 4.dp, start = 2.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MappColors.Text,
    )
}
