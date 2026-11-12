package br.com.mapp.overlay

import android.annotation.SuppressLint
import android.content.Context
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
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.reader.ReaderStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Ícone flutuante do copiloto (fica por cima da Uber/99/inDrive enquanto o copiloto está ativo).
 * Toque = abre/fecha o painel de ajustes rápidos; arraste = muda de lugar.
 * Só mexe nas configurações do próprio MApp — nunca toca no app de corrida.
 */
class QuickBubbleView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val actions: Actions,
) {
    interface Actions {
        fun previousProfile()
        fun nextProfile()
        /** Altera o perfil ativo (ex.: mínimo de R$/km) e salva. */
        fun updateProfile(change: (FilterProfile) -> FilterProfile)
        fun toggleVoice()
        fun openApp()
        fun stopCopilot()
    }

    private val prefs = OverlayPrefs.of(context)
    private val density = context.resources.displayMetrics.density

    private var bubbleAttached = false
    private var panelAttached = false
    private var profile: FilterProfile = FilterProfile()
    private var profileCount = 1
    private var reader: ReaderStatus.Snapshot = ReaderStatus.Snapshot()

    // ------------------------------------------------------------------ bolha

    private val bubble = TextView(context).apply {
        text = "M"
        contentDescription = "MApp – ajustes rápidos"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GREEN)
            setStroke(dp(2), WHITE)
        }
        elevation = dp(6).toFloat()
        isClickable = true
        setOnClickListener { togglePanel() }
    }

    private val bubbleParams = WindowManager.LayoutParams(
        dp(BUBBLE_DP),
        dp(BUBBLE_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(OverlayPrefs.KEY_BUBBLE_X, context.resources.displayMetrics.widthPixels - dp(BUBBLE_DP + 4))
        y = prefs.getInt(OverlayPrefs.KEY_BUBBLE_Y, (context.resources.displayMetrics.heightPixels * 0.45).toInt())
        title = "MApp – ícone"
    }

    // ------------------------------------------------------------------ painel

    private val profileName = text(15f, bold = true).apply { gravity = Gravity.CENTER }
    private val perKmValue = text(15f, bold = true)
    private val perHourValue = text(15f, bold = true)
    private val pickupValue = text(15f, bold = true)
    private val minPriceValue = text(15f, bold = true)
    private val voiceButton = button("") { actions.toggleVoice(); refreshVoice() }
    private val readerText = text(12f, color = SECONDARY)

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(PANEL_BG)
        }
        elevation = dp(8).toFloat()
        minimumWidth = dp(PANEL_WIDTH_DP)

        addView(text(12f, color = SECONDARY).apply { text = "AJUSTES RÁPIDOS" })
        addView(readerText, fullWidth(4))
        addView(button("📋 Copiar última leitura") { copyReading() }, fullWidth(4))
        addView(row(
            button("‹") { actions.previousProfile() },
            profileName,
            button("›") { actions.nextProfile() },
            labelWeight = true,
        ))
        addView(stepper("R$/km mínimo", perKmValue,
            { actions.updateProfile { it.copy(minPerKm = step(it.minPerKm, -0.10, 0.0, 1.50)) } },
            { actions.updateProfile { it.copy(minPerKm = step(it.minPerKm, +0.10, 0.0, 1.50)) } }))
        addView(stepper("R$/hora mínimo", perHourValue,
            { actions.updateProfile { it.copy(minPerHour = step(it.minPerHour, -5.0, 0.0, 30.0)) } },
            { actions.updateProfile { it.copy(minPerHour = step(it.minPerHour, +5.0, 0.0, 30.0)) } }))
        addView(stepper("Busca máxima (km)", pickupValue,
            { actions.updateProfile { it.copy(maxPickupKm = step(it.maxPickupKm, -0.5, 0.5, 4.0)) } },
            { actions.updateProfile { it.copy(maxPickupKm = step(it.maxPickupKm, +0.5, 0.5, 4.0)) } }))
        addView(stepper("Valor mínimo (R$)", minPriceValue,
            { actions.updateProfile { it.copy(minPrice = step(it.minPrice, -1.0, 0.0, 8.0)) } },
            { actions.updateProfile { it.copy(minPrice = step(it.minPrice, +1.0, 0.0, 8.0)) } }))
        addView(voiceButton, fullWidth(8))
        addView(row(
            button("Abrir MApp") { closePanel(); actions.openApp() },
            button("Parar copiloto", color = RED) { closePanel(); actions.stopCopilot() },
            labelWeight = false,
        ), fullWidth(6))

        // Toque fora do painel fecha o painel.
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false
        }
    }

    private val panelParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MApp – ajustes rápidos"
    }

    init {
        installDrag()
        paintBubble()
        refreshVoice()
        bindProfile()
    }

    // ------------------------------------------------------------------ API

    fun show() {
        if (bubbleAttached) return
        clampBubble()
        try {
            windowManager.addView(bubble, bubbleParams)
            bubbleAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível mostrar o ícone (permissão de sobreposição?)", e)
        }
    }

    fun updateProfile(active: FilterProfile, count: Int) {
        profile = active
        profileCount = count
        bindProfile()
    }

    fun updateReader(snapshot: ReaderStatus.Snapshot) {
        val wasConnected = reader.connected
        reader = snapshot
        if (panelAttached) bindReader()
        if (wasConnected != snapshot.connected) paintBubble()
    }

    fun destroy() {
        closePanel()
        if (bubbleAttached) {
            try { windowManager.removeViewImmediate(bubble) } catch (e: Exception) { Log.w(TAG, "Falha ao remover ícone", e) }
            bubbleAttached = false
        }
    }

    // ------------------------------------------------------------------ painel

    private fun togglePanel() = if (panelAttached) closePanel() else openPanel()

    private fun openPanel() {
        if (panelAttached) return
        refreshVoice()
        bindProfile()
        bindReader()
        val dm = context.resources.displayMetrics
        val width = dp(PANEL_WIDTH_DP)
        val bubbleOnRight = bubbleParams.x + dp(BUBBLE_DP) / 2 > dm.widthPixels / 2
        panelParams.x = if (bubbleOnRight) max(0, bubbleParams.x - width - dp(8)) else bubbleParams.x + dp(BUBBLE_DP + 8)
        panelParams.y = bubbleParams.y.coerceIn(0, max(0, dm.heightPixels - dp(PANEL_HEIGHT_DP)))
        try {
            windowManager.addView(panel, panelParams)
            panelAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir o painel", e)
        }
    }

    private fun closePanel() {
        if (!panelAttached) return
        try { windowManager.removeViewImmediate(panel) } catch (e: Exception) { Log.w(TAG, "Falha ao fechar painel", e) }
        panelAttached = false
    }

    private fun bindProfile() {
        val p = profile
        profileName.text = if (profileCount > 1) p.name else "${p.name} (único)"
        perKmValue.text = p.minPerKm?.let { money(it) } ?: "—"
        perHourValue.text = p.minPerHour?.let { money(it) } ?: "—"
        pickupValue.text = p.maxPickupKm?.let { String.format(PT_BR, "%.1f km", it) } ?: "—"
        minPriceValue.text = p.minPrice?.let { money(it) } ?: "—"
    }

    private fun bindReader() {
        val r = reader
        readerText.text = buildString {
            append(if (r.connected) "✅ Leitor conectado" else "⚠️ Leitor DESLIGADO – ative a Acessibilidade")
            append("\nÚltima leitura: ${r.lastPlatform ?: "—"} ${ReaderStatus.ago(r.lastEventAt)} (${r.lastTextCount} textos)")
            append("\nÚltima tela com R$: ${ReaderStatus.ago(r.lastMoneyAt)}")
            if (r.lastMoneyAt > 0) append(if (r.lastMoneyRecognized) " → oferta reconhecida" else " → NÃO reconhecida")
        }
        readerText.setTextColor(if (r.connected) SECONDARY else YELLOW)
    }

    private fun copyReading() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("MApp leitura", ReaderStatus.report()))
        Toast.makeText(context, "Leitura copiada. Cole no chat do Claude.", Toast.LENGTH_LONG).show()
    }

    /** Verde = leitor ligado; cinza com "!" = Acessibilidade desligada. */
    private fun paintBubble() {
        val ok = reader.connected
        bubble.text = if (ok) "M" else "M!"
        (bubble.background as? GradientDrawable)?.setColor(if (ok) GREEN else GRAY)
    }

    private fun refreshVoice() {
        voiceButton.text = if (OverlayPrefs.isVoiceEnabled(context)) "🔊 Voz ligada" else "🔇 Voz desligada"
    }

    // ------------------------------------------------------------------ arrastar

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; closePanel() }
                    if (dragging && bubbleAttached) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        updateBubble()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge() else if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** Gruda na borda mais próxima e salva a posição. */
    private fun snapToEdge() {
        val w = context.resources.displayMetrics.widthPixels
        bubbleParams.x = if (bubbleParams.x + dp(BUBBLE_DP) / 2 > w / 2) w - dp(BUBBLE_DP + 4) else dp(4)
        clampBubble()
        updateBubble()
        prefs.edit()
            .putInt(OverlayPrefs.KEY_BUBBLE_X, bubbleParams.x)
            .putInt(OverlayPrefs.KEY_BUBBLE_Y, bubbleParams.y)
            .apply()
    }

    private fun clampBubble() {
        val dm = context.resources.displayMetrics
        bubbleParams.x = bubbleParams.x.coerceIn(0, max(0, dm.widthPixels - dp(BUBBLE_DP)))
        bubbleParams.y = bubbleParams.y.coerceIn(0, max(0, dm.heightPixels - dp(BUBBLE_DP)))
    }

    private fun updateBubble() {
        if (!bubbleAttached) return
        try { windowManager.updateViewLayout(bubble, bubbleParams) } catch (e: Exception) { Log.w(TAG, "Falha ao mover ícone", e) }
    }

    // ------------------------------------------------------------------ views

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun text(sizeSp: Float, color: Int = WHITE, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, color: Int = BUTTON_BG, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(first: View, middle: View, last: View? = null, labelWeight: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        if (labelWeight) {
            addView(first)
            addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            last?.let { addView(it) }
        } else {
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun stepper(label: String, value: TextView, minus: () -> Unit, plus: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(12f, color = SECONDARY).apply { text = label })
                addView(value)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("−") { minus() })
            addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
            addView(button("+") { plus() })
        }

    private fun fullWidth(topMarginDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topMarginDp) }

    companion object {
        private const val TAG = "MApp/Bubble"
        private const val BUBBLE_DP = 52
        private const val PANEL_WIDTH_DP = 260
        private const val PANEL_HEIGHT_DP = 440

        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        private val WHITE = 0xFFFFFFFF.toInt()
        private val SECONDARY = 0xFFBDBDBD.toInt()
        private val GREEN = 0xFF2E7D32.toInt()
        private val RED = 0xFFC62828.toInt()
        private val GRAY = 0xFF616161.toInt()
        private val YELLOW = 0xFFFFD54F.toInt()
        private val PANEL_BG = 0xF2202124.toInt()
        private val BUTTON_BG = 0xFF3C4043.toInt()

        private fun money(v: Double): String = "R$ " + String.format(PT_BR, "%.2f", v)

        /** Soma [delta] (partindo de [start] se estiver vazio), arredonda e não deixa passar de [min]. */
        fun step(current: Double?, delta: Double, min: Double, start: Double): Double {
            val base = current ?: start
            val next = Math.round((base + delta) * 100.0) / 100.0
            return next.coerceAtLeast(min)
        }
    }
}
