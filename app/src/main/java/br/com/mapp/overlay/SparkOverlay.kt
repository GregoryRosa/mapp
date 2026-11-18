package br.com.mapp.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import br.com.mapp.core.bubble.BubbleStyle
import br.com.mapp.core.bubble.Spark
import br.com.mapp.core.bubble.SparkBurst
import br.com.mapp.core.bubble.SparkLevel

/**
 * Faíscas do ícone "M" quando a corrida começa (a bolha estourando). Elas saem para **fora** do
 * botão, então o efeito tem a **janela própria** — maior que o ícone, sem receber toques —, aberta
 * só enquanto dura e fechada no fim: fora do efeito não existe janela nenhuma nem redesenho.
 *
 * As posições vêm do [SparkBurst] (Kotlin puro, com teste); aqui só se desenha.
 */
internal class SparkOverlay(private val host: OverlayHost) {

    private val view = SparkView(host.context) { remove() }

    private val params = WindowManager.LayoutParams(
        1,
        1,
        host.type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MApp – efeito do ícone"
    }

    private var attached = false

    /**
     * Solta o efeito em volta do botão. [centerX]/[centerY] = centro do ícone na tela e [iconPx] =
     * o lado dele; com o efeito desligado nos ajustes não faz nada.
     */
    fun burst(style: BubbleStyle, centerX: Int, centerY: Int, iconPx: Int) {
        val level = style.sparkLevel
        if (level == SparkLevel.OFF || iconPx <= 0) return
        val radius = iconPx / 2f
        // Cabe a faísca mais longe (alcance do nível) com uma folga para o risco e o anel.
        val side = (2 * radius * (1f + level.reach + MARGIN)).toInt()
        params.width = side
        params.height = side
        params.x = centerX - side / 2
        params.y = centerY - side / 2
        if (!attach()) return
        view.start(style, radius)
    }

    fun destroy() = remove()

    private fun attach(): Boolean {
        try {
            if (attached) {
                host.windowManager.updateViewLayout(view, params)
            } else {
                host.windowManager.addView(view, params)
                attached = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível mostrar o efeito do ícone", e)
            attached = false
            return false
        }
        return true
    }

    private fun remove() {
        if (!attached) return
        attached = false
        try {
            host.windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao remover o efeito do ícone", e)
        }
    }

    private companion object {
        private const val TAG = "MApp/Spark"

        /** Folga da janela além do alcance das faíscas (em raios do botão). */
        const val MARGIN = 0.35f
    }
}

/** Desenha as faíscas e os anéis do estouro até o efeito acabar, e então avisa para fechar a janela. */
private class SparkView(context: Context, private val onDone: () -> Unit) : View(context) {

    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var level = SparkLevel.OFF
    private var sparks: List<Spark> = emptyList()
    private var colors = intArrayOf(WHITE, WHITE, WHITE)
    private var radius = 0f
    private var startedAt = 0L
    private var finished = false

    /** Começa o efeito com as cores do botão ([BubbleStyle.logoStart]/[logoEnd] e branco). */
    fun start(style: BubbleStyle, iconRadius: Float) {
        level = style.sparkLevel
        sparks = SparkBurst.sparks(level)
        colors = intArrayOf(style.logoStart, style.logoEnd, WHITE)
        radius = iconRadius
        startedAt = System.currentTimeMillis()
        finished = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = System.currentTimeMillis() - startedAt
        if (level == SparkLevel.OFF || elapsed > level.durationMs) {
            if (!finished) {
                finished = true
                post { onDone() } // fora do desenho: a janela sai depois que este quadro termina
            }
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        for (i in 0 until level.rings) {
            val alpha = SparkBurst.ringAlpha(i, level, elapsed)
            if (alpha <= 0f) continue
            ring.color = colors[0]
            ring.alpha = alpha(alpha)
            ring.strokeWidth = radius * RING_W
            canvas.drawCircle(cx, cy, SparkBurst.ringRadius(i, level, elapsed, radius), ring)
        }
        sparks.forEach { s ->
            val state = SparkBurst.state(s, level, elapsed, radius) ?: return@forEach
            spark.color = colors[s.color.coerceIn(0, colors.lastIndex)]
            spark.alpha = alpha(state.alpha)
            spark.strokeWidth = state.width
            canvas.drawLine(cx + state.tailX, cy + state.tailY, cx + state.x, cy + state.y, spark)
        }
        postInvalidateOnAnimation()
    }

    private fun alpha(fraction: Float): Int = (255 * fraction).toInt().coerceIn(0, 255)

    private companion object {
        val WHITE: Int = 0xFFFFFFFF.toInt()

        /** Grossura do anel do estouro, em raios do botão. */
        const val RING_W = 0.08f
    }
}
