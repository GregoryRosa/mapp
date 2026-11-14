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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.route.RideRoute
import br.com.mapp.core.route.RouteStop
import br.com.mapp.core.route.StopKind
import br.com.mapp.maps.MapViews
import br.com.mapp.maps.toOsm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.osmdroid.views.overlay.Marker
import kotlin.math.abs
import kotlin.math.max

/**
 * Cartão flutuante (View tradicional, sem XML/Compose): cabeçalho na cor do semáforo com as
 * informações escolhidas em "Estilo do cartão" ([CardHeaderView]), alertas de zona, mini mapa e fotos
 * da rua (Street View) da coleta, paradas e destino. Arrastável pela barra de título; não bloqueia toques fora dele.
 * Reutilizável: [dismiss] só tira da tela; [destroy] libera o mapa.
 */
class OfferCardView(
    private val host: OverlayHost,
    private val onClose: () -> Unit,
    /** "+1": conta esta oferta como corrida pega na jornada. */
    private val onCount: () -> Unit = {},
) {
    /** Botão "+1" do cabeçalho: só aparece com jornada iniciada. */
    enum class CountState { HIDDEN, READY, DONE }

    private val context: Context = host.context
    private val windowManager: WindowManager = host.windowManager
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Cabeçalho (cor do semáforo + as duas linhas de "Estilo do cartão") ----
    private val minimizeButton = headerButton("–", "Minimizar") { toggleMinimized() }
    private val closeButton = headerButton("✕", "Fechar") { onClose() }
    private val countButton = headerButton("+1", "Contar corrida na jornada") { onCount() }.apply {
        visibility = View.GONE
    }
    private val headerView = CardHeaderView(context, listOf(countButton, minimizeButton, closeButton))
    private val header = headerView.view
    private var profile: FilterProfile? = null

    /** Muda o estilo na tela do app: o cartão aberto já mostra o novo. */
    private val layoutListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            OverlayPrefs.KEY_CARD_LAYOUT -> offer?.let { headerView.bind(it, profile, OverlayPrefs.cardLayout(context)) }
            OverlayPrefs.KEY_STREETVIEW_ON, OverlayPrefs.KEY_STREETVIEW_KEY -> if (attached && !minimized) {
                photoCells.values.forEach { it.reset() }
                renderPhotos()
                applyMinimized()
            }
        }
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

    // ---- Fotos da rua (Street View): A, B… lado a lado embaixo do mapa; toque amplia ----
    private val photosRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
    }
    private val photoCells = LinkedHashMap<String, PhotoCell>()
    private var zoomedLetter: String? = null
    private var photosOfferId: Any? = null

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        addView(reasonsBox)
        addView(mapView, LinearLayout.LayoutParams(dp(CARD_WIDTH_DP - 24), dp(MAP_HEIGHT_DP)).apply { topMargin = dp(8) })
        addView(mapNote, topMargin(2))
        addView(
            photosRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
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
        host.type,
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

    /** READY = "+1" (contar); DONE = "✓" (já contada); HIDDEN = sem jornada. */
    fun setCountState(state: CountState) {
        countButton.visibility = if (state == CountState.HIDDEN) View.GONE else View.VISIBLE
        countButton.text = if (state == CountState.DONE) "✓" else "+1"
        countButton.contentDescription = if (state == CountState.DONE) "Corrida já contada" else "Contar corrida na jornada"
    }

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
        scope.cancel()
        if (!destroyed) mapView.onDetach()
        destroyed = true
    }

    // ---------------------------------------------------------------------

    private fun bind(o: EvaluatedOffer) {
        headerView.bind(o, profile, OverlayPrefs.cardLayout(context))
        bindReasons(o)
        if (!minimized) renderPhotos()
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
        // Paradas no caminho: as letras são as do mini mapa (A coleta, B… paradas, última = destino).
        RideRoute.stopsLabel(o.raw)?.let { label ->
            val letters = RideRoute.of(o).filter { it.kind == StopKind.STOP }.joinToString(", ") { it.letter }
            reasonsBox.addView(reasonLine("● Com $label no caminho ($letters)", CUSTOM_TEXT))
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
        val route = RideRoute.of(o)
        val hasPoints = route.any { it.point != null } || current != null
        mapView.visibility = if (hasPoints) View.VISIBLE else View.GONE
        // O semáforo aparece antes da geocodificação; os pontos chegam logo depois (ou nunca, sem rede).
        val missing = route.any { it.address != null && it.point == null }
        mapNote.visibility = if (missing) View.VISIBLE else View.GONE
        applyMinimized()
        if (!hasPoints) return
        // Mesmos pontos e zonas: o mapa já está certo (redesenhar recarrega tiles e trava o cartão).
        val key = Pair(route, zones)
        if (key == mapKey && (hereMarker != null) == (current != null)) return
        mapKey = key
        hereMarker = MapViews.renderOffer(mapView, zones, current, route)
    }

    /**
     * Fotos da coleta, paradas e destino ([StreetView.pick]). Só busca com o Card aberto (cada foto
     * conta no limite do mês); o mesmo ponto não busca de novo.
     */
    private fun renderPhotos() {
        val o = offer ?: return
        val stops = if (OverlayPrefs.streetViewEnabled(context)) StreetView.pick(RideRoute.of(o)) else emptyList()
        // Oferta nova começa com as fotos lado a lado (sem a ampliada da anterior).
        val offerId = o.raw.platform to o.raw.capturedAt
        if (offerId != photosOfferId) {
            photosOfferId = offerId
            zoomedLetter?.let { toggleZoom(it) }
        }
        if (stops.map { it.letter } != photoCells.keys.toList()) {
            photoCells.values.forEach { it.reset() }
            photoCells.clear()
            photosRow.removeAllViews()
            zoomedLetter = null
            stops.forEach { stop ->
                val cell = PhotoCell(stop)
                photosRow.addView(
                    cell.view,
                    LinearLayout.LayoutParams(0, dp(PHOTO_HEIGHT_DP), 1f).apply {
                        if (photoCells.isNotEmpty()) marginStart = dp(6)
                    },
                )
                photoCells[stop.letter] = cell
            }
        }
        stops.forEach { photoCells[it.letter]?.bind(it) }
        photosRow.visibility = if (stops.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Toque numa foto: ela ocupa a largura toda (as outras somem); outro toque volta. */
    private fun toggleZoom(letter: String) {
        zoomedLetter = if (zoomedLetter == letter) null else letter
        val zoom = zoomedLetter
        photoCells.forEach { (l, cell) ->
            cell.view.visibility = if (zoom == null || zoom == l) View.VISIBLE else View.GONE
            (cell.view.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(if (zoom == l) PHOTO_ZOOM_HEIGHT_DP else PHOTO_HEIGHT_DP)
                marginStart = if (zoom == l || l == photoCells.keys.first()) 0 else dp(6)
                cell.view.layoutParams = this
            }
        }
        if (attached) {
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao redimensionar cartão", e)
            }
        }
    }

    /** Uma foto: letra do mapa no canto (mesma cor do marcador), crédito e data embaixo. */
    private inner class PhotoCell(stop: RouteStop) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val status = text(11f, color = SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(20), dp(6), dp(4))
        }
        private val credit = text(9f, color = 0xDDFFFFFF.toInt()).apply {
            setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        private val badge = text(12f, bold = true).apply {
            text = stop.letter
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MapViews.colorOf(stop.kind))
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
        }
        val view = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(PHOTO_BG)
                cornerRadius = 10f * density
            }
            clipToOutline = true
            contentDescription = "Foto da rua: ${MapViews.titleOf(stop)}"
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(credit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
            setOnClickListener { toggleZoom(stop.letter) }
        }
        private var loadedId: String? = null
        private var job: Job? = null

        fun bind(stop: RouteStop) {
            val point = stop.point
            if (point == null) {
                reset()
                showMissing(if (stop.address != null) "Localizando endereço…" else "Endereço não lido")
                return
            }
            val id = StreetView.cacheKey(point)
            if (id == loadedId) return
            reset()
            loadedId = id
            StreetViewPhotos.cached(point)?.let { show(it); return }
            showMissing("Carregando foto…")
            job = scope.launch {
                val result = StreetViewPhotos.load(context, point)
                if (loadedId != id) return@launch
                show(result)
                // Falha passageira (rede, chave): tenta de novo no próximo desenho do Card.
                if (result is StreetViewPhotos.Result.Missing && result.retry) loadedId = null
            }
        }

        fun reset() {
            job?.cancel()
            job = null
            loadedId = null
        }

        private fun show(result: StreetViewPhotos.Result) {
            when (result) {
                is StreetViewPhotos.Result.Photo -> {
                    image.setImageBitmap(result.bitmap)
                    status.visibility = View.GONE
                    credit.text = result.credit
                    credit.visibility = View.VISIBLE
                }
                is StreetViewPhotos.Result.Missing -> showMissing(result.message)
            }
        }

        private fun showMissing(message: String) {
            image.setImageDrawable(null)
            status.text = message
            status.visibility = View.VISIBLE
            credit.visibility = View.GONE
        }
    }

    private fun toggleMinimized() {
        minimized = !minimized
        prefs.edit().putBoolean(OverlayPrefs.KEY_CARD_MINIMIZED, minimized).apply()
        if (!minimized) renderPhotos()
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
        val hasContent = listOf(reasonsBox, mapView, mapNote, photosRow).any { it.visibility == View.VISIBLE }
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
        const val PHOTO_HEIGHT_DP = 96
        const val PHOTO_ZOOM_HEIGHT_DP = 182
        val PHOTO_BG = 0xFF2C2F33.toInt()

        val CARD_BG = 0xF0202124.toInt()
        val PRIMARY = 0xFFFFFFFF.toInt()
        val SECONDARY = 0xFFBDBDBD.toInt()
        val DANGER_TEXT = 0xFFFF5252.toInt()
        val WARNING_TEXT = 0xFFFFD54F.toInt()
        val HIGH_DEMAND_TEXT = 0xFF69F0AE.toInt()
        val CUSTOM_TEXT = 0xFF64B5F6.toInt()
    }
}
