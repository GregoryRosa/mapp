package br.com.mapp.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import br.com.mapp.core.card.AddressItem
import br.com.mapp.core.card.AddressRow
import br.com.mapp.core.card.CardFilter
import br.com.mapp.core.card.CardFilters
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.card.CardMetric
import br.com.mapp.core.card.CardValues
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Signal
import br.com.mapp.core.route.RideRoute
import br.com.mapp.maps.MapViews

/**
 * Cabeçalho colorido do cartão (cor do semáforo) com as duas linhas escolhidas em
 * "Estilo do cartão": em cima texto corrido (valor · tempo · nota), embaixo números grandes com
 * barrinha de cor e, se escolhida, a linha de endereços em texto pequeno (uma por ponto A, B, C…,
 * com a letra na cor do marcador do mini mapa) e as etiquetas dos filtros que casaram (Card → Filtros).
 * [trailing] = botões à direita da linha de cima (minimizar/fechar).
 * Usado pelo [OfferCardView] e pela prévia da tela de estilo.
 */
class CardHeaderView(private val context: Context, private val trailing: List<TextView> = emptyList()) {
    private val density = context.resources.displayMetrics.density

    private val platformChip = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat() }
    }
    private val topText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        addView(platformChip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        addView(topText, LinearLayout.LayoutParams(0, WRAP, 1f))
        trailing.forEach { addView(it) }
    }
    private val bottomRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, 0)
    }
    private val addressBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
    }

    /** Etiquetas dos filtros que casaram (Card → Filtros), cada uma na cor do filtro. */
    private val tagsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, 0)
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), if (trailing.isEmpty()) dp(12) else dp(4), dp(8))
        addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(bottomRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(addressBox, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(tagsRow, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /**
     * [filters] = filtros do usuário que casaram com a oferta, na ordem da lista (`CardFilters.matching`):
     * o 1º que pinta dá a cor do fundo; todos aparecem como etiqueta.
     */
    fun bind(o: EvaluatedOffer, profile: FilterProfile?, layout: CardLayout, filters: List<CardFilter> = emptyList()) {
        // Cor do fundo: filtro do usuário > corrida com parada (roxo) > semáforo. As barrinhas de cada
        // item continuam com o semáforo.
        val withStop = RideRoute.stopCount(o.raw) > 0
        val paint = CardFilters.paintColor(filters)
        val darkText = if (paint != null) CardFilters.darkText(paint) else !withStop && o.signal == Signal.YELLOW
        val fg = if (darkText) DARK_TEXT else WHITE
        view.background = ColorDrawable(paint ?: if (withStop) STOP_PURPLE else signalColor(o.signal))
        trailing.forEach { it.setTextColor(fg) }

        platformChip.visibility = if (layout.showPlatform) View.VISIBLE else View.GONE
        platformChip.text = o.raw.platform.label
        platformChip.setTextColor(if (darkText) WHITE else DARK_TEXT)
        (platformChip.background as GradientDrawable).setColor(if (darkText) DARK_TEXT else WHITE)

        // Tamanho das letras escolhido em "Estilo do cartão" (% do padrão).
        val ts = layout.topFont / 100f
        val bs = layout.bottomFont / 100f
        platformChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * ts)
        topText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * ts)
        topText.setTextColor(fg)
        topText.text = topLine(o, profile, layout.top, ts)

        bottomRow.removeAllViews()
        bottomRow.visibility = if (layout.bottom.isEmpty()) View.GONE else View.VISIBLE
        val valueSp = bs * when (layout.bottom.size) {
            1, 2 -> 34f
            3 -> 30f
            else -> 24f
        }
        layout.bottom.forEach { metric ->
            bottomRow.addView(cell(metric, o, profile, fg, valueSp, bs), LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        addressBox.removeAllViews()
        val rows = CardValues.addressRows(o, layout.address)
        addressBox.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.forEach { addressBox.addView(addressRow(it, fg, layout.addressFont / 100f), LinearLayout.LayoutParams(MATCH, WRAP)) }

        bindTags(filters, fg)
    }

    /** Até [MAX_TAGS] etiquetas ("Área de risco", "Aeroporto"…) e "+2" para as que não couberam. */
    private fun bindTags(filters: List<CardFilter>, fg: Int) {
        tagsRow.removeAllViews()
        tagsRow.visibility = if (filters.isEmpty()) View.GONE else View.VISIBLE
        filters.take(MAX_TAGS).forEach { f ->
            val tag = TextView(context).apply {
                text = f.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (CardFilters.darkText(f.color)) DARK_TEXT else WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(if (filters.size == 1) 260 else 120)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                // Borda na cor do texto do Card: a etiqueta aparece mesmo quando é ela que pinta o fundo.
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(f.color)
                    setStroke(dp(1), fg)
                }
            }
            tagsRow.addView(tag, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        if (filters.size > MAX_TAGS) {
            tagsRow.addView(
                TextView(context).apply {
                    text = "+${filters.size - MAX_TAGS}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(fg)
                },
            )
        }
    }

    /**
     * "Ⓐ Rua Augusta, 1200 · 2,1 km · 6 min" em texto pequeno. O endereço ocupa o espaço que sobra e é
     * cortado com "…", para distância e tempo nunca sumirem.
     */
    private fun addressRow(row: AddressRow, fg: Int, scale: Float): View {
        val sp = ADDRESS_SP * scale
        val badgeSize = dp(Math.round(16 * scale))
        val badge = TextView(context).apply {
            text = row.letter
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp - 2f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MapViews.colorOf(row.kind))
                setStroke(dp(1), WHITE)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(1))
            addView(badge, LinearLayout.LayoutParams(badgeSize, badgeSize).apply { marginEnd = dp(6) })
            row.cells.forEachIndexed { i, cell ->
                val isAddress = cell.item == AddressItem.ADDRESS
                val tv = TextView(context).apply {
                    text = if (i > 0) "  ·  ${cell.text}" else cell.text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                    setTextColor(fg)
                    if (!isAddress) typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    if (isAddress) ellipsize = TextUtils.TruncateAt.END
                }
                addView(tv, if (isAddress) LinearLayout.LayoutParams(0, WRAP, 1f) else LinearLayout.LayoutParams(WRAP, WRAP))
            }
        }
    }

    /** "R$ 29,20 · 1h00m · ★ 4,20" — valor maior; item vermelho pelo perfil ganha fundo branco. */
    private fun topLine(o: EvaluatedOffer, profile: FilterProfile?, metrics: List<CardMetric>, scale: Float): CharSequence {
        val sb = SpannableStringBuilder()
        metrics.forEachIndexed { i, metric ->
            if (i > 0) sb.append("  ·  ")
            val start = sb.length
            sb.append(CardValues.full(metric, o))
            val end = sb.length
            if (metric == CardMetric.PRICE) sb.setSpan(AbsoluteSizeSpan(Math.round(19 * scale), true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (CardValues.tone(metric, o, profile) == Signal.RED) {
                sb.setSpan(BackgroundColorSpan(WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(RED_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun cell(metric: CardMetric, o: EvaluatedOffer, profile: FilterProfile?, fg: Int, valueSp: Float, scale: Float): View {
        val label = TextView(context).apply {
            text = CardValues.shortLabel(metric)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            setTextColor(fg)
            alpha = 0.85f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tone = CardValues.tone(metric, o, profile)
        val bar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(tone?.let { toneColor(it) } ?: 0)
                if (tone != null) setStroke(dp(1), 0x99FFFFFF.toInt())
            }
            visibility = if (tone == null) View.INVISIBLE else View.VISIBLE
        }
        val value = TextView(context).apply {
            text = CardValues.compact(metric, o)
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(minOf(12, valueSp.toInt()), valueSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val valueRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar, LinearLayout.LayoutParams(dp(4), dp(valueSp.toInt() - 4)).apply { marginEnd = dp(4) })
            addView(value, LinearLayout.LayoutParams(0, dp(valueSp.toInt() + 6), 1f))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(4), 0)
            addView(label)
            addView(valueRow)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** Letras da linha de endereços (100%). */
        private const val ADDRESS_SP = 12f
        /** Etiquetas de filtro que cabem numa linha do Card. */
        private const val MAX_TAGS = 2
        val WHITE = 0xFFFFFFFF.toInt()
        val DARK_TEXT = 0xFF212121.toInt()
        private val RED_BG = 0xFFC62828.toInt()
        /** Fundo do Card (e cor da notificação) de corrida com parada no caminho. */
        val STOP_PURPLE = 0xFF7B1FA2.toInt()

        fun signalColor(signal: Signal): Int = when (signal) {
            Signal.GREEN -> 0xFF2E7D32.toInt()
            Signal.YELLOW -> 0xFFF9A825.toInt()
            Signal.RED -> RED_BG
        }

        /** Cores fortes da barrinha (aparecem sobre qualquer cor de fundo). */
        fun toneColor(signal: Signal): Int = when (signal) {
            Signal.GREEN -> 0xFF00E676.toInt()
            Signal.YELLOW -> 0xFFFFEA00.toInt()
            Signal.RED -> 0xFFFF1744.toInt()
        }
    }
}
