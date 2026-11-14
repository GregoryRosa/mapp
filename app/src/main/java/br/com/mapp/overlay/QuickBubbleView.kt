package br.com.mapp.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.com.mapp.core.filter.FilterCriterion
import br.com.mapp.core.goal.DayGoal
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftSummary
import br.com.mapp.reader.ReaderStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Ícone flutuante do copiloto (fica por cima da Uber/99/inDrive enquanto o copiloto está ativo).
 * Toque = abre o menu circular (botões redondos com ícone em volta do "M"); arraste = muda de lugar.
 * A engrenagem do menu abre o painel completo de ajustes rápidos.
 * Só mexe nas configurações do próprio MApp — nunca toca no app de corrida.
 */
class QuickBubbleView(
    private val host: OverlayHost,
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

        // Jornada (▶ no painel do "M")
        fun startShift()
        fun pauseShift()
        fun endShift()
        /** Conta a última oferta vista como corrida pega. */
        fun countLastOffer()
        fun undoLastTrip()
        /** Corrida em andamento terminou / foi cancelada (correção manual do automático). */
        fun finishPendingTrip()
        fun cancelPendingTrip()

        /** Marca um pin no local atual (zona para desenhar depois no editor). */
        fun markPin(type: ZoneType)
    }

    private val context: Context = host.context
    private val windowManager: WindowManager = host.windowManager
    private val prefs = OverlayPrefs.of(context)
    private val density = context.resources.displayMetrics.density

    private var bubbleAttached = false
    private var panelAttached = false
    private var profile: FilterProfile = FilterProfile()
    private var profileCount = 1
    private var reader: ReaderStatus.Snapshot = ReaderStatus.Snapshot()
    private var shift: Shift? = null
    private var summary: ShiftSummary? = null
    private var lastOfferLabel: String? = null
    private var confirmEnd = false
    private var pinCount = 0

    // ------------------------------------------------------------------ bolha

    private val bubble = LogoBubble(context, ringBg = RING_BG).apply {
        contentDescription = "MApp – ajustes rápidos"
        statusColor = NEON_GREEN
        elevation = dp(6).toFloat()
        isClickable = true
        setOnClickListener { onBubbleTap() }
    }

    private val bubbleParams = WindowManager.LayoutParams(
        dp(BUBBLE_DP),
        dp(BUBBLE_DP),
        host.type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(OverlayPrefs.KEY_BUBBLE_X, context.resources.displayMetrics.widthPixels - dp(BUBBLE_DP + 4))
        y = prefs.getInt(OverlayPrefs.KEY_BUBBLE_Y, (context.resources.displayMetrics.heightPixels * 0.45).toInt())
        title = "MApp – ícone"
    }

    // ------------------------------------------------------------------ textos do dia (em volta do "M")

    /** Números do dia junto ao "M" (mesma conta de Ganhos e gastos → Hoje) e a meta diária. */
    data class DayInfo(val km: Double, val carCost: Double, val gross: Double, val goal: Double?, val hasData: Boolean)

    private var day: DayInfo? = null
    private var labelsAttached = false
    private val kmLabel = dayLabel(DAY_KM)
    private val costLabel = dayLabel(DAY_COST)
    private val grossLabel = dayLabel(DAY_GROSS)

    /**
     * Só números, centralizados com o "M": km e custo em cima, valor bruto embaixo.
     * Contorno preto para ler em fundo claro; sem fundo e não recebe toques.
     */
    private val labelsRoot = FrameLayout(context).apply {
        addView(kmLabel, labelParams(top = 0))
        addView(costLabel, labelParams(top = LABEL_LINE_DP))
        addView(grossLabel, labelParams(top = 2 * LABEL_LINE_DP + BUBBLE_DP))
        visibility = View.GONE
    }

    private val labelsParams = WindowManager.LayoutParams(
        dp(LABEL_WIDTH_DP),
        dp(3 * LABEL_LINE_DP + BUBBLE_DP),
        host.type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MApp – resumo do dia"
    }

    // ------------------------------------------------------------------ menu circular

    private var radialAttached = false
    private val shiftButton = roundButton(NeonIcon.Kind.PLAY, NEON_YELLOW, "Jornada") { onShiftTap() }
    private val tripButton = roundButton(NeonIcon.Kind.PLUS_ONE, NEON_CYAN, "Contar última oferta como corrida") {
        actions.countLastOffer()
    }
    private val settingsButton = roundButton(NeonIcon.Kind.GEAR, NEON_GREEN, "Ajustes rápidos") {
        closeRadial(); openPanel()
    }
    private val voiceRound = roundButton(NeonIcon.Kind.VOICE_ON, NEON_PURPLE, "Voz") {
        actions.toggleVoice(); refreshVoice()
    }
    private val homeButton = roundButton(NeonIcon.Kind.HOME, NEON_BLUE, "Abrir MApp") {
        closeRadial(); actions.openApp()
    }
    private val powerButton = roundButton(NeonIcon.Kind.POWER, NEON_RED, "Parar copiloto") {
        closeRadial(); actions.stopCopilot()
    }
    private val pinButton = roundButton(NeonIcon.Kind.PIN, NEON_ORANGE, "Marcar este local (zona para desenhar depois)") {
        showPinChoices()
    }
    /** Ordem de cima para baixo no arco; o 📍 fica no meio (mais fácil de alcançar). */
    private val radialItems = listOf(settingsButton, shiftButton, tripButton, pinButton, voiceRound, homeButton, powerButton)

    /** 2º passo do 📍: escolher o tipo. Um toque marca e fecha. */
    private val pinChoices = listOf(
        Triple(NeonIcon.Kind.WARNING, NEON_RED, ZoneType.DANGER),
        Triple(NeonIcon.Kind.FLAME, NEON_GREEN, ZoneType.HIGH_DEMAND),
        Triple(NeonIcon.Kind.STAR, NEON_BLUE, ZoneType.CUSTOM),
    ).map { (kind, color, type) ->
        roundButton(kind, color, "Marcar: ${type.label}") { closeRadial(); actions.markPin(type) }
    }
    private var radialOnRight = true
    private val closeButton = roundButton(NeonIcon.Kind.CLOSE, WHITE, "Fechar menu", sizeDp = BUBBLE_DP) { closeRadial() }

    @SuppressLint("ClickableViewAccessibility")
    private val radialRoot = FrameLayout(context).apply {
        (radialItems + pinChoices).forEach { addView(it, FrameLayout.LayoutParams(dp(ITEM_DP), dp(ITEM_DP))) }
        addView(closeButton, FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)))
        // Toque fora dos botões (ou fora da janela) fecha o menu.
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE || e.actionMasked == MotionEvent.ACTION_UP) {
                closeRadial(); true
            } else e.actionMasked == MotionEvent.ACTION_DOWN
        }
    }

    private val radialParams = WindowManager.LayoutParams(
        dp(RADIAL_SIZE_DP),
        dp(RADIAL_SIZE_DP),
        host.type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MApp – menu"
    }

    // ---- ▶/⏸ segurado (estado; funções mais abaixo)
    private val holdRing = HoldRing(NEON_RED, 4f * density)
    private var holdAnim: ValueAnimator? = null
    private var holdDone = false
    private var holdLeft = false
    private val endShiftRunnable = Runnable {
        holdDone = true
        stopHoldVisual()
        shiftButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        actions.endShift()
    }

    // ------------------------------------------------------------------ painel

    private val profileName = text(15f, bold = true).apply { gravity = Gravity.CENTER }
    /** Um slider de faixa por critério do perfil (igual à tela Perfis); grava ao soltar o dedo. */
    private val sliders = FilterCriterion.entries.associateWith { c ->
        RangeSliderView(context, c) { r -> actions.updateProfile { c.apply(it, r) } }
    }
    private val filterBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(SECTION_BG) }
        addView(text(11f, color = SECONDARY).apply { text = "SEMÁFORO DO PERFIL · ARRASTE E SOLTE" })
        sliders.forEach { (c, slider) ->
            addView(text(12f, color = WHITE).apply { text = c.title }, fullWidth(8))
            addView(slider, fullWidth(2))
        }
    }
    private val voiceButton = button("") { actions.toggleVoice(); refreshVoice() }
    private val readerText = text(12f, color = SECONDARY)

    // ---- Jornada ----
    private val shiftState = text(14f, bold = true)
    private val startButton = button("▶  Iniciar jornada", color = GREEN) { actions.startShift() }
    private val pauseButton = button("⏸ Pausar") { actions.pauseShift() }
    private val resumeButton = button("▶ Continuar", color = GREEN) { actions.startShift() }
    private val endButton = button("⏹ Encerrar", color = RED) { onEndTapped() }
    private val runningRow = row(pauseButton, endButton, labelWeight = false)
    private val pausedRow = row(resumeButton, button("⏹ Encerrar", color = RED) { onEndTapped() }, labelWeight = false)
    private val shiftMetrics = text(13f).apply { setLineSpacing(0f, 1.1f) }
    private val lastOfferText = text(11f, color = SECONDARY)
    private val tripRow = row(
        button("+1 Contar última", color = BLUE) { actions.countLastOffer() },
        button("↶ Desfazer") { actions.undoLastTrip() },
        labelWeight = false,
    )
    private val pendingText = text(13f, bold = true, color = NEON_GREEN)
    private val pendingRow = row(
        button("✓ Finalizada", color = GREEN) { actions.finishPendingTrip() },
        button("✕ Cancelada", color = RED) { actions.cancelPendingTrip() },
        labelWeight = false,
    )
    private val shiftBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(SECTION_BG) }
        addView(text(11f, color = SECONDARY).apply { text = "JORNADA" })
        addView(shiftState)
        addView(startButton, fullWidth(6))
        addView(runningRow, fullWidth(4))
        addView(pausedRow, fullWidth(4))
        addView(shiftMetrics, fullWidth(6))
        addView(pendingText, fullWidth(6))
        addView(pendingRow, fullWidth(2))
        addView(tripRow, fullWidth(4))
        addView(lastOfferText, fullWidth(2))
    }

    // ---- Pin rápido (marca o local atual; a zona é desenhada depois) ----
    private val pinInfo = text(11f, color = SECONDARY)
    private val pinBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(SECTION_BG) }
        addView(text(11f, color = SECONDARY).apply { text = "📍 MARCAR ESTE LOCAL" })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                Triple("⚠ Perigo", RED, ZoneType.DANGER),
                Triple("🔥 Demanda", GREEN, ZoneType.HIGH_DEMAND),
                Triple("★ Outro", BLUE, ZoneType.CUSTOM),
            ).forEachIndexed { i, (label, color, type) ->
                addView(
                    button(label, color) { actions.markPin(type) }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(4) },
                )
            }
        }, fullWidth(4))
        addView(pinInfo, fullWidth(2))
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))

        addView(text(12f, color = SECONDARY).apply { text = "MApp ${ReaderStatus.versionOf(context)} · AJUSTES RÁPIDOS" })
        addView(readerText, fullWidth(4))
        addView(pinBox, fullWidth(8))
        addView(shiftBox, fullWidth(8))
        addView(row(
            button("‹") { actions.previousProfile() },
            profileName,
            button("›") { actions.nextProfile() },
            labelWeight = true,
        ))
        addView(filterBox, fullWidth(6))
        addView(voiceButton, fullWidth(8))
        addView(row(
            button("Abrir MApp") { closePanel(); actions.openApp() },
            button("Parar copiloto", color = RED) { closePanel(); actions.stopCopilot() },
            labelWeight = false,
        ), fullWidth(6))
    }

    /** Painel rolável: com a jornada aberta ele pode passar da altura da tela. */
    private val panelRoot = ScrollView(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(PANEL_BG)
        }
        elevation = dp(8).toFloat()
        isVerticalScrollBarEnabled = true
        addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Toque fora do painel fecha o painel.
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false
        }
    }

    private val panelParams = WindowManager.LayoutParams(
        0,
        WindowManager.LayoutParams.WRAP_CONTENT,
        host.type,
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
        installHoldToEnd()
        paintBubble()
        refreshVoice()
        bindProfile()
        bindShift()
        bindPins()
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
            return
        }
        placeLabels()
        try {
            windowManager.addView(labelsRoot, labelsParams)
            labelsAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível mostrar o resumo do dia", e)
        }
        bindDay()
    }

    /** Números e meta do dia; null = esconde os textos e o anel da meta. */
    fun updateDay(info: DayInfo?) {
        day = info
        bindDay()
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

    /** Jornada atual (null = nenhuma), resumo e a última oferta que pode ser contada. */
    fun updateShift(current: Shift?, sum: ShiftSummary?, lastOffer: String?) {
        shift = current
        summary = sum
        lastOfferLabel = lastOffer
        if (current == null) confirmEnd = false
        if (panelAttached) { bindShift(); relayoutPanel() }
        bindRadial()
        paintBubble()
    }

    /** Quantos pins esperam virar zona. */
    fun updatePins(count: Int) {
        pinCount = count
        bindPins()
    }

    fun destroy() {
        closeRadial()
        closePanel()
        if (labelsAttached) {
            try { windowManager.removeViewImmediate(labelsRoot) } catch (e: Exception) { Log.w(TAG, "Falha ao remover resumo", e) }
            labelsAttached = false
        }
        if (bubbleAttached) {
            try { windowManager.removeViewImmediate(bubble) } catch (e: Exception) { Log.w(TAG, "Falha ao remover ícone", e) }
            bubbleAttached = false
        }
    }

    // ------------------------------------------------------------------ menu circular

    private fun onBubbleTap() = when {
        panelAttached -> closePanel()
        radialAttached -> closeRadial()
        else -> openRadial()
    }

    private fun onShiftTap() {
        val s = shift
        if (s == null || s.paused) actions.startShift() else actions.pauseShift()
    }

    /**
     * Abre os botões em meia-lua ao redor do "M", virados para o meio da tela.
     * Perto do topo/rodapé o "M" desliza o necessário para o arco caber na tela.
     */
    private fun openRadial() {
        if (radialAttached) return
        refreshVoice()
        bindRadial()
        val dm = context.resources.displayMetrics
        val size = dp(RADIAL_SIZE_DP)
        val half = size / 2
        val bubbleHalf = dp(BUBBLE_DP) / 2
        val reach = dp(RADIUS_DP + ITEM_DP / 2 + 4)
        val minY = reach - bubbleHalf
        val maxY = dm.heightPixels - reach - bubbleHalf
        if (minY <= maxY && bubbleParams.y !in minY..maxY) {
            bubbleParams.y = bubbleParams.y.coerceIn(minY, maxY)
            updateBubble()
        }
        val cx = bubbleParams.x + bubbleHalf
        val cy = bubbleParams.y + bubbleHalf
        radialParams.x = cx - half
        radialParams.y = cy - half

        radialOnRight = cx > dm.widthPixels / 2
        radialItems.forEach { it.visibility = View.VISIBLE }
        pinChoices.forEach { it.visibility = View.GONE }
        placeOnArc(radialItems, ARC_DEG)
        (closeButton.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = half - dp(BUBBLE_DP) / 2
            topMargin = half - dp(BUBBLE_DP) / 2
        }
        try {
            windowManager.addView(radialRoot, radialParams)
            radialAttached = true
            animateRadial(radialItems)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir o menu", e)
        }
    }

    /** Distribui [items] num arco de [arcDeg] graus virado para o meio da tela. */
    private fun placeOnArc(items: List<View>, arcDeg: Double) {
        val half = dp(RADIAL_SIZE_DP) / 2
        val n = items.size
        val step = if (n > 1) arcDeg / (n - 1) else 0.0
        val radius = dp(RADIUS_DP)
        items.forEachIndexed { i, v ->
            // y cresce para baixo: 270° = cima, 90° = baixo, 180° = esquerda, 0° = direita.
            val deg = if (radialOnRight) 180.0 + arcDeg / 2 - i * step else -arcDeg / 2 + i * step
            val rad = Math.toRadians(deg)
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = half + (radius * cos(rad)).toInt() - dp(ITEM_DP) / 2
            lp.topMargin = half + (radius * sin(rad)).toInt() - dp(ITEM_DP) / 2
            v.layoutParams = lp
        }
    }

    /** 📍 tocado: o arco troca para os 3 tipos de zona (⚠ perigo, 🔥 demanda, ★ outro). */
    private fun showPinChoices() {
        if (!radialAttached) return
        radialItems.forEach {
            // Animação de abertura cortada no meio não pode deixar o botão apagado na próxima vez.
            it.animate().cancel()
            it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f; it.translationX = 0f; it.translationY = 0f
            it.visibility = View.GONE
        }
        pinChoices.forEach { it.visibility = View.VISIBLE; it.alpha = 1f }
        placeOnArc(pinChoices, PIN_ARC_DEG)
        animateRadial(pinChoices)
    }

    /** Botões saem do centro para a posição final. */
    private fun animateRadial(items: List<View>) {
        val half = dp(RADIAL_SIZE_DP) / 2f
        items.forEachIndexed { i, v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            v.translationX = half - (lp.leftMargin + dp(ITEM_DP) / 2f)
            v.translationY = half - (lp.topMargin + dp(ITEM_DP) / 2f)
            val targetAlpha = v.alpha // "+1" fica apagado sem jornada
            v.scaleX = 0.3f; v.scaleY = 0.3f; v.alpha = 0f
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(targetAlpha)
                .setStartDelay(i * 18L).setDuration(170L).start()
        }
    }

    private fun closeRadial() {
        cancelHold()
        if (!radialAttached) return
        try { windowManager.removeViewImmediate(radialRoot) } catch (e: Exception) { Log.w(TAG, "Falha ao fechar menu", e) }
        radialAttached = false
    }

    private fun bindRadial() {
        val s = shift
        val running = s != null && !s.paused
        shiftButton.setImageDrawable(NeonIcon(if (running) NeonIcon.Kind.PAUSE else NeonIcon.Kind.PLAY, NEON_YELLOW))
        shiftButton.contentDescription = when {
            s == null -> "Iniciar jornada"
            running -> "Pausar jornada (segure 3 segundos para encerrar)"
            else -> "Continuar jornada (segure 3 segundos para encerrar)"
        }
        tripButton.alpha = if (s != null) 1f else 0.4f
        val voiceOn = OverlayPrefs.isVoiceEnabled(context)
        voiceRound.setImageDrawable(NeonIcon(if (voiceOn) NeonIcon.Kind.VOICE_ON else NeonIcon.Kind.VOICE_OFF, NEON_PURPLE))
        voiceRound.contentDescription = if (voiceOn) "Voz ligada" else "Voz desligada"
    }

    // ------------------------------------------------------------------ ▶/⏸: toque = play/pausa, segurar 3 s = encerrar

    @SuppressLint("ClickableViewAccessibility")
    private fun installHoldToEnd() {
        shiftButton.foreground = holdRing
        shiftButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holdDone = false
                    holdLeft = false
                    v.isPressed = true
                    if (shift != null) startHold() // sem jornada não há o que encerrar
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val out = e.x < 0 || e.y < 0 || e.x > v.width || e.y > v.height
                    if (out && !holdLeft) { holdLeft = true; v.isPressed = false; cancelHold() }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    cancelHold()
                    if (!holdDone && !holdLeft) v.performClick() // toque simples: play/pausa
                    true
                }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; cancelHold(); true }
                else -> false
            }
        }
    }

    /** Anel vermelho enche em volta do botão; completo = jornada encerrada. */
    private fun startHold() {
        cancelHold()
        holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HOLD_TO_END_MS
            addUpdateListener { holdRing.progress = it.animatedValue as Float }
            start()
        }
        shiftButton.postDelayed(endShiftRunnable, HOLD_TO_END_MS)
    }

    private fun cancelHold() {
        shiftButton.removeCallbacks(endShiftRunnable)
        stopHoldVisual()
    }

    private fun stopHoldVisual() {
        holdAnim?.cancel()
        holdAnim = null
        holdRing.progress = 0f
    }

    // ------------------------------------------------------------------ painel

    private fun openPanel() {
        if (panelAttached) return
        refreshVoice()
        bindProfile()
        bindReader()
        bindShift()
        val dm = context.resources.displayMetrics
        val width = dp(PANEL_WIDTH_DP).coerceAtMost(dm.widthPixels - dp(BUBBLE_DP + 16))
        panelParams.width = width
        val bubbleOnRight = bubbleParams.x + dp(BUBBLE_DP) / 2 > dm.widthPixels / 2
        panelParams.x = if (bubbleOnRight) max(0, bubbleParams.x - width - dp(8)) else bubbleParams.x + dp(BUBBLE_DP + 8)
        measurePanel()
        try {
            windowManager.addView(panelRoot, panelParams)
            panelAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir o painel", e)
        }
    }

    private fun closePanel() {
        if (!panelAttached) return
        try { windowManager.removeViewImmediate(panelRoot) } catch (e: Exception) { Log.w(TAG, "Falha ao fechar painel", e) }
        panelAttached = false
        confirmEnd = false
    }

    /** Altura = conteúdo, limitada à tela (o resto rola); posição perto do ícone. */
    private fun measurePanel() {
        val dm = context.resources.displayMetrics
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxHeight = dm.heightPixels - dp(48)
        panelParams.height = panel.measuredHeight.coerceAtMost(maxHeight)
        panelParams.y = bubbleParams.y.coerceIn(0, max(0, dm.heightPixels - panelParams.height - dp(24)))
    }

    private fun relayoutPanel() {
        measurePanel()
        try { windowManager.updateViewLayout(panelRoot, panelParams) } catch (e: Exception) { Log.w(TAG, "Falha ao ajustar painel", e) }
    }

    // ------------------------------------------------------------------ jornada

    private fun onEndTapped() {
        if (!confirmEnd) {
            confirmEnd = true
            bindShift()
            panel.postDelayed({ if (confirmEnd) { confirmEnd = false; bindShift() } }, CONFIRM_MS)
            return
        }
        confirmEnd = false
        actions.endShift()
    }

    private fun bindShift() {
        val s = shift
        val sum = summary
        startButton.visibility = if (s == null) View.VISIBLE else View.GONE
        runningRow.visibility = if (s != null && !s.paused) View.VISIBLE else View.GONE
        pausedRow.visibility = if (s != null && s.paused) View.VISIBLE else View.GONE
        shiftMetrics.visibility = if (s != null && sum != null) View.VISIBLE else View.GONE
        tripRow.visibility = if (s != null) View.VISIBLE else View.GONE
        lastOfferText.visibility = if (s != null) View.VISIBLE else View.GONE
        val pending = s?.pendingTrip
        pendingText.visibility = if (pending != null) View.VISIBLE else View.GONE
        pendingRow.visibility = if (pending != null) View.VISIBLE else View.GONE
        if (pending != null) {
            pendingText.text = "🚕 Em corrida: ${listOfNotNull(pending.category, ShiftMath.money(pending.price)).joinToString(" ")}"
        }

        shiftState.text = when {
            s == null -> "Parada · toque ▶ ao começar a rodar"
            s.paused -> "⏸ Pausada"
            else -> "● Rodando"
        }
        shiftState.setTextColor(if (s != null && !s.paused) AMBER else WHITE)
        val endLabel = if (confirmEnd) "Confirmar?" else "⏹ Encerrar"
        endButton.text = endLabel
        ((pausedRow as LinearLayout).getChildAt(1) as? TextView)?.text = endLabel
        if (sum != null) shiftMetrics.text = ShiftMath.lines(sum).joinToString("\n")
        lastOfferText.text = lastOfferLabel?.let { "Última oferta: $it" } ?: "Nenhuma oferta lida ainda"
    }
    private fun bindPins() {
        pinInfo.text = if (pinCount == 0) "Só o ponto agora; desenhe a zona depois em Zonas"
        else "$pinCount pin(s) para desenhar em Zonas"
    }

    private fun bindProfile() {
        val p = profile
        profileName.text = if (profileCount > 1) p.name else "${p.name} (único)"
        sliders.forEach { (c, slider) -> slider.bind(c.range(p)) }
    }

    private fun bindReader() {
        val r = reader
        // O painel só existe com o copiloto ligado.
        readerText.text = ReaderStatus.lines(r, copilotRunning = true).joinToString("\n")
        readerText.setTextColor(if (r.connected) SECONDARY else YELLOW)
    }

    /** Laranja = ligado sem corrida; verde = em corrida; cinza com "!" = Acessibilidade desligada. */
    private fun paintBubble() {
        val ok = reader.connected
        val color = when {
            !ok -> GRAY_LIGHT
            shift?.pendingTrip != null -> NEON_GREEN // em corrida
            else -> NEON_ORANGE                      // ligado, sem corrida
        }
        bubble.statusColor = color
        bubble.warning = !ok
        bubble.thickRing = shift?.paused == false // anel mais grosso = jornada rodando
        bubble.invalidate()
    }

    private fun refreshVoice() {
        voiceButton.text = if (OverlayPrefs.isVoiceEnabled(context)) "🔊 Voz ligada" else "🔇 Voz desligada"
        if (radialAttached) bindRadial()
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
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; closePanel(); closeRadial() }
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
        placeLabels()
        if (labelsAttached) {
            try { windowManager.updateViewLayout(labelsRoot, labelsParams) } catch (e: Exception) { Log.w(TAG, "Falha ao mover resumo", e) }
        }
    }

    // ------------------------------------------------------------------ resumo do dia

    /** Textos centralizados com o "M" (a janela pode sair um pouco da tela; os números são curtos). */
    private fun placeLabels() {
        labelsParams.x = bubbleParams.x + dp(BUBBLE_DP) / 2 - dp(LABEL_WIDTH_DP) / 2
        labelsParams.y = bubbleParams.y - dp(2 * LABEL_LINE_DP)
    }

    private fun bindDay() {
        val d = day
        // Anel da meta: enche com o bruto do dia; batida = anel com o gradiente do MApp por fora.
        val fraction = d?.let { DayGoal.fraction(it.gross, it.goal) }
        val reached = d != null && DayGoal.reached(d.gross, d.goal)
        bubble.setGoal(fraction?.toFloat(), reached)
        bubble.contentDescription = "MApp – ajustes rápidos" +
            (fraction?.let { " · meta do dia ${(it * 100).toInt()}%" } ?: "")
        val show = d != null && d.hasData
        labelsRoot.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || d == null) return
        kmLabel.text = ShiftMath.num(d.km, 1)
        costLabel.text = ShiftMath.num(d.carCost, 2)
        grossLabel.text = ShiftMath.num(d.gross, 2)
        grossLabel.color = if (reached) DAY_GOAL else DAY_GROSS
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

    /** Número pequeno (tamanho fixo em dp, cabe na janela mesmo com fonte grande) com contorno preto. */
    private fun dayLabel(color: Int) = OutlineLabel(context, LABEL_SP * density, 3f * density).apply { this.color = color }

    private fun labelParams(top: Int) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(LABEL_LINE_DP),
    ).apply { topMargin = dp(top) }

    /** Botão redondo: fundo preto, anel neon e ícone da mesma cor. */
    private fun roundButton(
        kind: NeonIcon.Kind,
        color: Int,
        description: String,
        sizeDp: Int = ITEM_DP,
        onClick: () -> Unit,
    ) = ImageView(context).apply {
        setImageDrawable(NeonIcon(kind, color))
        contentDescription = description
        val pad = sizeDp * 26 / 100
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(RING_BG)
            setStroke(dp(3), color)
        }
        elevation = dp(6).toFloat()
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun fullWidth(topMarginDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topMarginDp) }

    companion object {
        private const val TAG = "MApp/Bubble"
        private const val BUBBLE_DP = 52
        private const val LABEL_LINE_DP = 17
        private const val LABEL_SP = 13f
        private const val LABEL_WIDTH_DP = 96
        private const val PANEL_WIDTH_DP = 290
        private const val CONFIRM_MS = 4_000L
        private const val HOLD_TO_END_MS = 3_000L
        private const val ITEM_DP = 56
        private const val RADIUS_DP = 118
        private const val RADIAL_SIZE_DP = 2 * (RADIUS_DP + ITEM_DP / 2 + 6)
        private const val ARC_DEG = 180.0
        private const val PIN_ARC_DEG = 100.0

        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        private val WHITE = 0xFFFFFFFF.toInt()
        private val SECONDARY = 0xFFBDBDBD.toInt()
        private val GREEN = 0xFF2E7D32.toInt()
        private val RED = 0xFFC62828.toInt()
        private val YELLOW = 0xFFFFD54F.toInt()
        private val PANEL_BG = 0xF2202124.toInt()
        private val SECTION_BG = 0xFF2D2F33.toInt()
        private val AMBER = 0xFFFFB300.toInt()
        private val BLUE = 0xFF1565C0.toInt()
        private val BUTTON_BG = 0xFF3C4043.toInt()
        private val RING_BG = 0xF0000000.toInt()
        private val DAY_KM = 0xFFF2F2F2.toInt()
        private val DAY_COST = 0xFFFFCC80.toInt()
        private val DAY_GROSS = 0xFF8EE69A.toInt()
        /** Lilás da marca: valor bruto com a meta do dia batida. */
        private val DAY_GOAL = 0xFFC9B5FF.toInt()
        private val GRAY_LIGHT = 0xFF9E9E9E.toInt()
        private val NEON_GREEN = 0xFF00E64D.toInt()
        private val NEON_YELLOW = 0xFFFFD600.toInt()
        private val NEON_CYAN = 0xFF00E5D4.toInt()
        private val NEON_PURPLE = 0xFFB040FF.toInt()
        private val NEON_BLUE = 0xFF2F8CFF.toInt()
        private val NEON_RED = 0xFFFF1744.toInt()
        private val NEON_ORANGE = 0xFFFF9100.toInt()

        private fun money(v: Double): String = "R$ " + String.format(PT_BR, "%.2f", v)

        /** Soma [delta] (partindo de [start] se estiver vazio), arredonda e não deixa passar de [min]. */
        fun step(current: Double?, delta: Double, min: Double, start: Double): Double {
            val base = current ?: start
            val next = Math.round((base + delta) * 100.0) / 100.0
            return next.coerceAtLeast(min)
        }
    }
}

/** Ícones de traço (estilo neon) desenhados em grade 24×24 — sem arquivos de imagem. */
private class NeonIcon(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { PLAY, PAUSE, PLUS_ONE, GEAR, VOICE_ON, VOICE_OFF, HOME, POWER, CLOSE, PIN, WARNING, FLAME, STAR }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        if (side <= 0f) return
        val u = side / 24f
        canvas.save()
        canvas.translate(b.exactCenterX() - side / 2, b.exactCenterY() - side / 2)
        canvas.scale(u, u)
        stroke.strokeWidth = 2f
        when (kind) {
            Kind.PLAY -> canvas.drawPath(path(7.5f, 4.5f, 7.5f, 19.5f, 19.5f, 12f, close = true), stroke)
            Kind.PAUSE -> { stroke.strokeWidth = 2.6f; canvas.drawLine(8.5f, 5f, 8.5f, 19f, stroke); canvas.drawLine(15.5f, 5f, 15.5f, 19f, stroke) }
            Kind.PLUS_ONE -> {
                canvas.drawLine(3f, 12f, 10f, 12f, stroke)
                canvas.drawLine(6.5f, 8.5f, 6.5f, 15.5f, stroke)
                fill.textSize = 15f
                canvas.drawText("1", 16.5f, 17.5f, fill)
            }
            Kind.GEAR -> {
                canvas.drawCircle(12f, 12f, 3f, stroke)
                canvas.drawCircle(12f, 12f, 6.5f, stroke)
                stroke.strokeWidth = 3f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = cos(a).toFloat(); val s = sin(a).toFloat()
                    canvas.drawLine(12f + 7f * c, 12f + 7f * s, 12f + 9.5f * c, 12f + 9.5f * s, stroke)
                }
            }
            Kind.VOICE_ON, Kind.VOICE_OFF -> {
                canvas.drawPath(path(3f, 9.5f, 7f, 9.5f, 12f, 5f, 12f, 19f, 7f, 14.5f, 3f, 14.5f, close = true), stroke)
                if (kind == Kind.VOICE_ON) {
                    canvas.drawArc(RectF(11f, 8.5f, 18f, 15.5f), -50f, 100f, false, stroke)
                    canvas.drawArc(RectF(10f, 5f, 22f, 19f), -50f, 100f, false, stroke)
                } else {
                    canvas.drawLine(15.5f, 9.5f, 20.5f, 14.5f, stroke)
                    canvas.drawLine(20.5f, 9.5f, 15.5f, 14.5f, stroke)
                }
            }
            Kind.HOME -> {
                canvas.drawPath(path(3f, 11.5f, 12f, 4f, 21f, 11.5f), stroke)
                canvas.drawPath(path(5.5f, 10f, 5.5f, 20f, 18.5f, 20f, 18.5f, 10f), stroke)
                canvas.drawPath(path(10f, 20f, 10f, 14.5f, 14f, 14.5f, 14f, 20f), stroke)
            }
            Kind.POWER -> {
                canvas.drawArc(RectF(4.5f, 5f, 19.5f, 20f), -55f, 290f, false, stroke)
                canvas.drawLine(12f, 3f, 12f, 11.5f, stroke)
            }
            Kind.CLOSE -> {
                canvas.drawLine(6f, 6f, 18f, 18f, stroke)
                canvas.drawLine(18f, 6f, 6f, 18f, stroke)
            }
            Kind.PIN -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 21.5f)
                    cubicTo(7f, 15.5f, 5f, 12.5f, 5f, 9.5f)
                    cubicTo(5f, 5.5f, 8.2f, 2.5f, 12f, 2.5f)
                    cubicTo(15.8f, 2.5f, 19f, 5.5f, 19f, 9.5f)
                    cubicTo(19f, 12.5f, 17f, 15.5f, 12f, 21.5f)
                    close()
                }, stroke)
                canvas.drawCircle(12f, 9.5f, 2.6f, stroke)
            }
            Kind.WARNING -> {
                canvas.drawPath(path(12f, 3.5f, 21.5f, 20f, 2.5f, 20f, close = true), stroke)
                canvas.drawLine(12f, 9.5f, 12f, 14f, stroke)
                canvas.drawCircle(12f, 17f, 0.9f, fill)
            }
            Kind.FLAME -> canvas.drawPath(Path().apply {
                moveTo(12f, 21.5f)
                cubicTo(7.5f, 21.5f, 5f, 18.5f, 5f, 15f)
                cubicTo(5f, 10.5f, 9.5f, 8.5f, 10f, 2.5f)
                cubicTo(13.5f, 5f, 15f, 8f, 14.5f, 11f)
                cubicTo(16f, 10f, 16.8f, 8.8f, 17f, 7.5f)
                cubicTo(18.5f, 9.5f, 19f, 12f, 19f, 15f)
                cubicTo(19f, 18.5f, 16.5f, 21.5f, 12f, 21.5f)
                close()
            }, stroke)
            Kind.STAR -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    pts[2 * i] = 12f + r * cos(a).toFloat()
                    pts[2 * i + 1] = 12.8f + r * sin(a).toFloat()
                }
                canvas.drawPath(path(*pts, close = true), stroke)
            }
        }
        canvas.restore()
    }

    private fun path(vararg p: Float, close: Boolean = false) = Path().apply {
        moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
        if (close) close()
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter; fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Arco de progresso desenhado por cima do anel do botão (segurar para encerrar). */
private class HoldRing(color: Int, private val width: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = width
        this.color = color
    }
    private val oval = RectF()

    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidateSelf() }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f) return
        oval.set(bounds)
        oval.inset(width / 2, width / 2)
        canvas.drawArc(oval, -90f, 360f * progress, false, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Texto de uma linha centralizado, com contorno (traço preto por baixo do preenchimento). */
private class OutlineLabel(context: Context, sizePx: Float, outlinePx: Float) : View(context) {
    var text: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }
    var color: Int = 0xFFFFFFFF.toInt()
        set(value) { if (field != value) { field = value; invalidate() } }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlinePx
        strokeJoin = Paint.Join.ROUND
        setColor(0xF0000000.toInt())
        textSize = sizePx
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = sizePx
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val x = width / 2f
        val fm = fill.fontMetrics
        val y = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, x, y, outline)
        fill.color = color
        canvas.drawText(text, x, y, fill)
    }
}

/**
 * Ícone flutuante com o logo do MApp: "M" em forma de rota (gradiente verde-água → lilás) com
 * ponto de partida e de chegada, mesmo desenho do app (`ui/MappLogo`). O anel mostra o status:
 * laranja = ligado sem corrida, verde = em corrida, cinza + "!" = leitor desligado.
 *
 * **Meta do dia** ([setGoal]): um preenchimento verde-água corre por cima do anel de status (a partir do
 * topo, sentido horário) e pulsa enquanto a meta não é batida. Em 100% ele se expande para fora,
 * vira um anel com o gradiente do MApp em volta do círculo e o anel de status volta a aparecer.
 */
private class LogoBubble(context: Context, private val ringBg: Int) : View(context) {
    var statusColor: Int = 0xFF00E64D.toInt()
    var warning = false
    var thickRing = false

    /** Fração 0..1 da meta (null = sem meta). */
    private var goal: Float? = null
    private var reached = false
    /** 0 = preenchimento sobre o anel de status; 1 = anel da meta por fora (meta batida). */
    private var expand = 0f
    private var expandAnim: ValueAnimator? = null
    private val pulseStart = System.currentTimeMillis()
    private val pulse = object : Runnable {
        override fun run() {
            invalidate()
            if (pulsing()) postDelayed(this, PULSE_FRAME_MS)
        }
    }

    private val teal = 0xFF5EE6D8.toInt()
    private val lilac = 0xFFB59CFF.toInt()
    private val gray = 0xFF9E9E9E.toInt()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val path = android.graphics.Path()
    private val arc = RectF()
    private var shaderSide = 0f
    private var shaderWarning = false

    /**
     * Meta do dia: [fraction] 0..1 (null = sem meta), [isReached] = 100%. A expansão para fora só é
     * animada quando a meta é batida com o ícone na tela (ao reabrir o ícone já aparece por fora).
     */
    fun setGoal(fraction: Float?, isReached: Boolean) {
        val had = goal != null
        goal = fraction
        val nowReached = fraction != null && isReached
        if (nowReached && !reached) {
            reached = true
            expandAnim?.cancel()
            if (had && isAttachedToWindow) {
                expandAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = EXPAND_MS
                    interpolator = android.view.animation.OvershootInterpolator(1.6f)
                    addUpdateListener { expand = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                expand = 1f
            }
        } else if (!nowReached && reached) {
            reached = false
            expandAnim?.cancel()
            expand = 0f
        }
        removeCallbacks(pulse)
        if (pulsing()) post(pulse)
        invalidate()
    }

    private fun pulsing(): Boolean = goal != null && !reached && isAttachedToWindow

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(pulse)
        if (pulsing()) post(pulse)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pulse)
        expandAnim?.cancel()
        if (reached) expand = 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val u = side / 24f
        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.translate(cx - side / 2, cy - side / 2)
        val c = side / 2

        // Com a meta batida o círculo encolhe um pouco para o anel da meta caber por fora.
        val e = expand
        val outerW = OUTER_W * u
        val scale = 1f - e.coerceIn(0f, 1.2f) * (outerW + OUTER_GAP * u) / c
        val statusW = u * if (thickRing) 2.2f else 1.5f

        canvas.save()
        canvas.scale(scale, scale, c, c)
        // Gradientes refeitos só quando o tamanho ou o aviso mudam (o pulso redesenha ~25×/s).
        if (shaderSide != side || shaderWarning != warning) {
            shaderSide = side
            shaderWarning = warning
            bg.shader = android.graphics.RadialGradient(
                side * 0.35f, side * 0.3f, side * 0.75f, 0xFF16404A.toInt(), ringBg, android.graphics.Shader.TileMode.CLAMP,
            )
            line.shader = android.graphics.LinearGradient(
                6.5f * u, 7.5f * u, 17.5f * u, 17.5f * u,
                if (warning) gray else teal, if (warning) gray else lilac, android.graphics.Shader.TileMode.CLAMP,
            )
            outer.shader = android.graphics.SweepGradient(c, c, intArrayOf(teal, lilac, 0xFF8F6BFF.toInt(), lilac, teal), null)
        }
        canvas.drawCircle(c, c, c, bg)
        ring.color = statusColor
        ring.strokeWidth = statusW
        canvas.drawCircle(c, c, c - statusW / 2, ring)
        drawLogo(canvas, u)

        // Meta em andamento: preenchimento verde-água (cor do MApp) por cima do anel de status, pulsando.
        val g = goal
        if (g != null && !reached) {
            val phase = ((System.currentTimeMillis() - pulseStart) % PULSE_MS) / PULSE_MS.toFloat()
            val beat = 0.5f + 0.5f * kotlin.math.cos(2 * Math.PI * phase).toFloat()
            arc.set(statusW / 2, statusW / 2, side - statusW / 2, side - statusW / 2)
            val sweep = maxOf(360f * g, 1f) // 0%: um ponto no topo mostra que há meta
            fill.shader = null
            fill.color = teal
            fill.alpha = (80 * beat).toInt()
            fill.strokeWidth = statusW * 2.2f
            canvas.drawArc(arc, -90f, sweep, false, fill)
            fill.alpha = (170 + 85 * beat).toInt()
            fill.strokeWidth = statusW
            canvas.drawArc(arc, -90f, sweep, false, fill)
        }
        canvas.restore()

        // Meta batida: o preenchimento sai do anel de status e vira o anel de fora com o gradiente.
        if (reached) {
            val k = e.coerceIn(0f, 1f)
            val from = (c - statusW / 2) * scale
            val to = c - outerW / 2
            val r = from + (to - from) * k
            val w = statusW * scale + (outerW - statusW * scale) * k
            outer.strokeWidth = w
            outer.alpha = 255
            canvas.drawCircle(c, c, r, outer)
            if (k < 1f) {
                // Verde-água do preenchimento some enquanto o gradiente aparece.
                fill.shader = null
                fill.color = teal
                fill.alpha = (255 * (1f - k)).toInt()
                fill.strokeWidth = w
                canvas.drawCircle(c, c, r, fill)
            }
        }
        canvas.restore()
    }

    private fun drawLogo(canvas: Canvas, u: Float) {
        val c1 = if (warning) gray else teal
        val c2 = if (warning) gray else lilac
        line.strokeWidth = 2.4f * u
        path.reset()
        path.moveTo(6.5f * u, 17f * u)
        path.lineTo(6.5f * u, 7.5f * u)
        path.lineTo(12f * u, 13.5f * u)
        path.lineTo(17.5f * u, 7.5f * u)
        path.lineTo(17.5f * u, 15f * u)
        canvas.drawPath(path, line)

        dot.style = Paint.Style.STROKE
        dot.strokeWidth = 1.1f * u
        dot.color = c1
        canvas.drawCircle(6.5f * u, 17.6f * u, 1.6f * u, dot)
        dot.style = Paint.Style.FILL
        dot.color = c2
        canvas.drawCircle(17.5f * u, 17.2f * u, 1.9f * u, dot)

        if (warning) {
            dot.color = 0xFFFFB300.toInt()
            canvas.drawCircle(19.5f * u, 4.5f * u, 3.6f * u, dot)
            badge.color = 0xFF000000.toInt()
            badge.textSize = 6f * u
            canvas.drawText("!", 19.5f * u, 6.6f * u, badge)
        }
    }

    private companion object {
        /** Pulso da meta: ciclo de 1,4 s desenhado a ~25 quadros/s (leve para a bateria). */
        const val PULSE_MS = 1_400L
        const val PULSE_FRAME_MS = 40L
        const val EXPAND_MS = 700L
        /** Anel da meta batida (em 1/24 do ícone) e o espaço até o círculo. */
        const val OUTER_W = 1.7f
        const val OUTER_GAP = 0.45f
    }
}
