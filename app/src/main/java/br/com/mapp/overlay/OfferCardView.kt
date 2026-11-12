package br.com.mapp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.maps.MapViews
import br.com.mapp.maps.toOsm
import org.osmdroid.views.overlay.Marker
import kotlin.math.abs
import kotlin.math.max

/**
 * Cartão flutuante (View tradicional, sem XML/Compose): cabeçalho na cor do semáforo com as
 * informações escolhidas em "Estilo do cartão" ([CardHeaderView]), alertas de zona e mini mapa. Arrastável pela barra de título; não bloqueia toques fora dele.
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
    private var mapKey: Any? = null
    private var hereMarker: Marker? = null

    // ---- Cabeçalho (cor do semáforo + as duas linhas de "Estilo do cartão") ----
    private val minimizeButton = headerButton("–", "Minimizar") { toggleMinimized() }
    private val closeButton = headerButton("✕", "Fechar") { onClose() }
    private val headerView = CardHeaderView(context, listOf(minimizeButton, closeButton))
    private val header = headerView.view
    private var profile: FilterProfile? = null

    /** Muda o estilo na tela do app: o cartão aberto já mostra o novo. */
    private val layoutListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OverlayPrefs.KEY_CARD_LAYOUT) offer?.let { headerView.bind(it, profile, OverlayPrefs.cardLayout(context)) }
    }

    // ---- Corpo: alertas e mini mapa (endereços já aparecem na própria Uber) ----
    private val reasonsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val mapNote = text(11f, color = SECONDARY).apply {
        text = "Coleta/destino ainda não localizados no mapa"
    }
    private val mapView = MapViews.create(context, interactive = false).apply {
        // O cartão entra e sai da janela várias vezes; o mapa só é destruído em destroy().
        setDestroyMode(false)
    }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        addView(reasonsBox)
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
        dp(CARD_WIDTH_DP), // largura fixa: textos longos não esticam o cartão
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
        prefs.registerOnSharedPreferenceChangeListener(layoutListener)
        applyMinimized()
    }

    val isShowing: Boolean get() = attached

    /** Mostra (ou atualiza) o cartão com a oferta avaliada. */
    fun show(offer: EvaluatedOffer, profile: FilterProfile?, current: GeoPoint?, zones: List<Zone>) {
        if (destroyed) return
        this.offer = offer
        this.profile = profile
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
        if (!attached || minimized) return
        val marker = hereMarker
        if (marker != null) {
            // GPS a cada segundo: só move o ponto azul, sem redesenhar zonas nem reenquadrar.
            marker.position = point.toOsm()
            mapView.invalidate()
        } else {
            renderMap()
        }
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
        prefs.unregisterOnSharedPreferenceChangeListener(layoutListener)
        if (!destroyed) mapView.onDetach()
        destroyed = true
    }

    // ---------------------------------------------------------------------

    private fun bind(o: EvaluatedOffer) {
        headerView.bind(o, profile, OverlayPrefs.cardLayout(context))
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
        // O semáforo aparece antes da geocodificação; os pontos chegam logo depois (ou nunca, sem rede).
        val missing = (!o.raw.pickupAddress.isNullOrBlank() && o.pickup == null) ||
            (!o.raw.dropoffAddress.isNullOrBlank() && o.dropoff == null)
        mapNote.visibility = if (missing) View.VISIBLE else View.GONE
        applyMinimized()
        if (!hasPoints) return
        // Mesmos pontos e zonas: o mapa já está certo (redesenhar recarrega tiles e trava o cartão).
        val key = Triple(o.pickup, o.dropoff, zones)
        if (key == mapKey && (hereMarker != null) == (current != null)) return
        mapKey = key
        hereMarker = MapViews.renderOffer(mapView, zones, current, o.pickup, o.dropoff)
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
        // Sem alerta e sem mapa o corpo some: fica só o cabeçalho com os números.
        val hasContent = listOf(reasonsBox, mapView, mapNote).any { it.visibility == View.VISIBLE }
        body.visibility = if (minimized || !hasContent) View.GONE else View.VISIBLE
        minimizeButton.text = if (minimized) "▢" else "–"
        minimizeButton.contentDescription = if (minimized) "Mostrar mapa" else "Esconder mapa"
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

    private fun reasonLine(content: String, color: Int, bold: Boolean = false) = text(13f, color = color, bold = bold).apply {
        text = content
        setPadding(0, dp(1), 0, dp(1))
    }

    private fun topMargin(marginDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(marginDp) }

    private companion object {
        const val TAG = "MApp/OfferCard"
        const val CARD_WIDTH_DP = 300
        const val MAP_HEIGHT_DP = 180

        val CARD_BG = 0xF0202124.toInt()
        val PRIMARY = 0xFFFFFFFF.toInt()
        val SECONDARY = 0xFFBDBDBD.toInt()
        val DANGER_TEXT = 0xFFFF5252.toInt()
        val WARNING_TEXT = 0xFFFFD54F.toInt()
        val HIGH_DEMAND_TEXT = 0xFF69F0AE.toInt()
        val CUSTOM_TEXT = 0xFF64B5F6.toInt()
    }
}
