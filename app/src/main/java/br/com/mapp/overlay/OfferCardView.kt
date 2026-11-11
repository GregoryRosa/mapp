package br.com.mapp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.maps.MapViews
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Cartão flutuante (View tradicional, sem XML/Compose): semáforo, valores, motivos,
 * alertas de zona e mini mapa. Arrastável pela barra de título; não bloqueia toques fora dele.
 * Reutilizável: [dismiss] só tira da tela; [destroy] libera o mapa.
 */
class OfferCardView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit,
) {
    private val prefs = OverlayPrefs.of(context)
    private val density = context.resources.displayMetrics.density

    private var attached = false
    private var destroyed = false
    private var minimized = prefs.getBoolean(OverlayPrefs.KEY_CARD_MINIMIZED, false)
    private var offer: EvaluatedOffer? = null
    private var current: GeoPoint? = null
    private var zones: List<Zone> = emptyList()

    // ---- Cabeçalho (faixa do semáforo) ----
    private val signalText = text(12f, bold = true)
    private val platformText = text(12f)
    private val priceText = text(22f, bold = true)
    private val minimizeButton = headerButton("–", "Minimizar") { toggleMinimized() }
    private val closeButton = headerButton("✕", "Fechar") { onClose() }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(4), dp(4))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(signalText)
            addView(platformText)
        }
        addView(labels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(View(context), LinearLayout.LayoutParams(dp(12), 1, 1f))
        addView(priceText)
        addView(minimizeButton)
        addView(closeButton)
    }

    // ---- Corpo ----
    private val perKmValue = text(18f, bold = true)
    private val perHourValue = text(18f, bold = true)
    private val netValue = text(18f, bold = true)
    private val netLabel = text(11f, color = SECONDARY)
    private val totalsText = text(12f, color = SECONDARY)
    private val addressText = text(12f, color = SECONDARY).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val reasonsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val mapNote = text(11f, color = SECONDARY).apply {
        text = "Coleta/destino não localizados no mapa"
    }
    private val mapView = MapViews.create(context, interactive = false).apply {
        // O cartão entra e sai da janela várias vezes; o mapa só é destruído em destroy().
        setDestroyMode(false)
    }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        val stats = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statCell(perKmValue, text(11f, color = SECONDARY).apply { text = "R$/km" }), weighted())
            addView(statCell(perHourValue, text(11f, color = SECONDARY).apply { text = "R$/hora" }), weighted())
            addView(statCell(netValue, netLabel), weighted())
        }
        addView(stats)
        addView(totalsText, topMargin(6))
        addView(addressText, topMargin(4))
        addView(reasonsBox, topMargin(6))
        addView(mapView, LinearLayout.LayoutParams(dp(CARD_WIDTH_DP - 24), dp(MAP_HEIGHT_DP)).apply { topMargin = dp(8) })
        addView(mapNote, topMargin(2))
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(CARD_BG)
            cornerRadius = 14f * density
            setStroke(dp(1), 0x33FFFFFF)
        }
        clipToOutline = true
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(OverlayPrefs.KEY_CARD_X, dp(8))
        y = prefs.getInt(OverlayPrefs.KEY_CARD_Y, dp(96))
        title = "MApp – oferta"
    }

    init {
        installDrag(header)
        header.setOnClickListener { toggleMinimized() }
        applyMinimized()
    }

    val isShowing: Boolean get() = attached

    /** Mostra (ou atualiza) o cartão com a oferta avaliada. */
    fun show(offer: EvaluatedOffer, current: GeoPoint?, zones: List<Zone>) {
        if (destroyed) return
        this.offer = offer
        this.current = current
        this.zones = zones
        bind(offer)
        if (!attached) {
            clampPosition()
            try {
                windowManager.addView(root, params)
                attached = true
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível mostrar o cartão (permissão de sobreposição?)", e)
            }
        }
    }

    fun updateCurrentPosition(point: GeoPoint?) {
        if (point == null || point == current) return
        current = point
        if (attached && !minimized) renderMap()
    }

    fun updateZones(zones: List<Zone>) {
        this.zones = zones
        if (attached && !minimized) renderMap()
    }

    /** Tira o cartão da tela (pode voltar com [show]). */
    fun dismiss() {
        if (!attached) return
        try {
            windowManager.removeViewImmediate(root)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao remover cartão", e)
        }
        attached = false
    }

    fun destroy() {
        dismiss()
        if (!destroyed) mapView.onDetach()
        destroyed = true
    }

    // ---------------------------------------------------------------------

    private fun bind(o: EvaluatedOffer) {
        val bg = signalColor(o.signal)
        val fg = if (o.signal == Signal.YELLOW) DARK_TEXT else PRIMARY
        header.background = ColorDrawable(bg)
        listOf(signalText, platformText, priceText, minimizeButton, closeButton).forEach { it.setTextColor(fg) }
        signalText.text = when (o.signal) {
            Signal.GREEN -> "VERDE"
            Signal.YELLOW -> "AMARELO"
            Signal.RED -> "VERMELHO"
        }
        platformText.text = o.raw.platform.label
        priceText.text = money(o.raw.price)

        val m = o.metrics
        perKmValue.text = number(m.perKm, 2)
        perHourValue.text = number(m.perHour, 0)
        netValue.text = m.netProfit?.let { money(it) } ?: "—"
        netValue.setTextColor(if ((m.netProfit ?: 0.0) < 0) DANGER_TEXT else PRIMARY)
        netLabel.text = m.netPerKm?.let { "Lucro líq. (${number(it, 2)}/km)" } ?: "Lucro líquido"

        totalsText.text = buildString {
            append("Total: ").append(number(m.totalKm, 1)).append(" km · ")
            append(m.totalMin?.toString() ?: "—").append(" min")
            if (o.raw.pickupKm != null || o.raw.pickupMin != null) {
                append("  |  Busca: ").append(number(o.raw.pickupKm, 1)).append(" km · ")
                append(o.raw.pickupMin?.toString() ?: "—").append(" min")
            }
            o.raw.passengerRating?.let { append("  |  ★ ").append(number(it, 2)) }
        }

        val addresses = listOfNotNull(
            o.raw.pickupAddress?.takeIf { it.isNotBlank() }?.let { "A: $it" },
            o.raw.dropoffAddress?.takeIf { it.isNotBlank() }?.let { "B: $it" },
        )
        addressText.text = addresses.joinToString("\n")
        addressText.visibility = if (addresses.isEmpty()) View.GONE else View.VISIBLE

        bindReasons(o)
        applyMinimized()
        if (!minimized) renderMap()
    }

    private fun bindReasons(o: EvaluatedOffer) {
        reasonsBox.removeAllViews()
        o.reasons.forEach { reason ->
            when {
                reason.contains("zona perigosa", ignoreCase = true) ->
                    reasonsBox.addView(reasonLine("⚠ $reason", DANGER_TEXT, bold = true))
                reason.contains("alta demanda", ignoreCase = true) ->
                    reasonsBox.addView(reasonLine("● $reason", HIGH_DEMAND_TEXT))
                else -> reasonsBox.addView(reasonLine("• $reason", WARNING_TEXT))
            }
        }
        // Zonas personalizadas não entram nos motivos do avaliador: mostra como informação.
        o.zoneHits.filter { it.zone.type == ZoneType.CUSTOM }.forEach { hit ->
            val onde = if (hit.role == PointRole.PICKUP) "Coleta" else "Destino"
            reasonsBox.addView(reasonLine("● $onde na zona ${hit.zone.name}", CUSTOM_TEXT))
        }
        reasonsBox.visibility = if (reasonsBox.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun renderMap() {
        val o = offer ?: return
        val hasPoints = o.pickup != null || o.dropoff != null || current != null
        mapView.visibility = if (hasPoints) View.VISIBLE else View.GONE
        val missing = (!o.raw.pickupAddress.isNullOrBlank() && o.pickup == null) ||
            (!o.raw.dropoffAddress.isNullOrBlank() && o.dropoff == null)
        mapNote.visibility = if (missing) View.VISIBLE else View.GONE
        if (hasPoints) MapViews.renderOffer(mapView, zones, current, o.pickup, o.dropoff)
    }

    private fun toggleMinimized() {
        minimized = !minimized
        prefs.edit().putBoolean(OverlayPrefs.KEY_CARD_MINIMIZED, minimized).apply()
        applyMinimized()
        if (!minimized) renderMap()
        if (attached) {
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao redimensionar cartão", e)
            }
        }
    }

    private fun applyMinimized() {
        body.visibility = if (minimized) View.GONE else View.VISIBLE
        minimizeButton.text = if (minimized) "▢" else "–"
        minimizeButton.contentDescription = if (minimized) "Expandir" else "Minimizar"
        root.minimumWidth = if (minimized) 0 else dp(CARD_WIDTH_DP)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging && attached) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (ex: Exception) {
                            Log.w(TAG, "Falha ao mover cartão", ex)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) savePosition() else v.performClick()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) savePosition()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun savePosition() {
        clampPosition()
        if (attached) {
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao reposicionar cartão", e)
            }
        }
        prefs.edit()
            .putInt(OverlayPrefs.KEY_CARD_X, params.x)
            .putInt(OverlayPrefs.KEY_CARD_Y, params.y)
            .apply()
    }

    /** Mantém ao menos parte do cartão visível (ex.: após girar a tela). */
    private fun clampPosition() {
        val dm = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, max(0, dm.widthPixels - dp(120)))
        params.y = params.y.coerceIn(0, max(0, dm.heightPixels - dp(72)))
    }

    // ---- Construção de views ----

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun text(sizeSp: Float, color: Int = PRIMARY, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun headerButton(symbol: String, description: String, onClick: () -> Unit) = TextView(context).apply {
        text = symbol
        contentDescription = description
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun statCell(value: TextView, label: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(value)
        addView(label)
    }

    private fun reasonLine(content: String, color: Int, bold: Boolean = false) = text(13f, color = color, bold = bold).apply {
        text = content
        setPadding(0, dp(1), 0, dp(1))
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun topMargin(marginDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(marginDp) }

    private companion object {
        const val TAG = "MApp/OfferCard"
        const val CARD_WIDTH_DP = 300
        const val MAP_HEIGHT_DP = 180

        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

        val CARD_BG = 0xF0202124.toInt()
        val PRIMARY = 0xFFFFFFFF.toInt()
        val SECONDARY = 0xFFBDBDBD.toInt()
        val DARK_TEXT = 0xFF212121.toInt()
        val DANGER_TEXT = 0xFFFF5252.toInt()
        val WARNING_TEXT = 0xFFFFD54F.toInt()
        val HIGH_DEMAND_TEXT = 0xFF69F0AE.toInt()
        val CUSTOM_TEXT = 0xFF64B5F6.toInt()

        fun signalColor(signal: Signal): Int = when (signal) {
            Signal.GREEN -> 0xFF2E7D32.toInt()
            Signal.YELLOW -> 0xFFF9A825.toInt()
            Signal.RED -> 0xFFC62828.toInt()
        }

        fun money(v: Double): String = "R$ " + String.format(PT_BR, "%.2f", v)

        fun number(v: Double?, decimals: Int): String =
            v?.let { String.format(PT_BR, "%.${decimals}f", it) } ?: "—"
    }
}
