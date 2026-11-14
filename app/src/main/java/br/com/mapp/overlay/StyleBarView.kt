package br.com.mapp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import br.com.mapp.core.bubble.ColorBar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Barra dos ajustes do botão "M" (View comum, sem Compose), com um botão para arrastar:
 * - [Kind.COLOR]: branco + arco-íris; o botão mostra a cor escolhida;
 * - [Kind.BACKGROUND]: a mesma barra, e o botão mostra a cor já escurecida do fundo;
 * - [Kind.SPEED]: passos ([steps]) de parado a rápido, com marquinhas.
 * Arrastar mostra na hora ([onChange]); grava ao soltar ([onCommit]). Toque sem arrastar pula
 * para o ponto tocado. Arrasto na horizontal não rola o painel; na vertical, rola.
 */
@SuppressLint("ViewConstructor")
class StyleBarView(
    context: Context,
    private val kind: Kind,
    private val steps: Int = 0,
    private val onChange: (Double) -> Unit,
    private val onCommit: (Double) -> Unit,
) : View(context) {

    enum class Kind { COLOR, BACKGROUND, SPEED }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Posição 0..1 do botão. */
    private var fraction = 0.5
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    private val thumbR = 12f * density
    private val trackH = 10f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = WHITE
    }
    private val rect = RectF()
    private var shaderWidth = -1

    /** Mostra a posição salva (ignorado enquanto o dedo arrasta, para não pular). */
    fun bind(f: Double) {
        if (dragging) return
        fraction = snap(f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = thumbR * 2 + 6 * density
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h.toInt())
    }

    // Nada de "left"/"right": colidiria com View.getLeft()/getRight().
    private val trackStart: Float get() = thumbR
    private val trackEnd: Float get() = width - thumbR
    private fun xOf(f: Double) = trackStart + f.toFloat() * (trackEnd - trackStart)
    private fun fractionAt(x: Float) = snap(((x - trackStart) / (trackEnd - trackStart)).toDouble().coerceIn(0.0, 1.0))

    private fun snap(f: Double): Double {
        val c = f.coerceIn(0.0, 1.0)
        if (steps < 2) return c
        return (c * (steps - 1)).roundToInt().toDouble() / (steps - 1)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val h = trackH / 2
        rect.set(trackStart - h, cy - h, trackEnd + h, cy + h)
        if (kind == Kind.SPEED) {
            track.shader = null
            track.color = TRACK_OFF
            canvas.drawRoundRect(rect, h, h, track)
            fill.color = SPEED_ON
            canvas.drawRoundRect(RectF(rect.left, rect.top, xOf(fraction) + h, rect.bottom), h, h, fill)
            if (steps >= 2) {
                fill.color = TICK
                for (i in 0 until steps) {
                    canvas.drawCircle(xOf(i.toDouble() / (steps - 1)), cy, 1.8f * density, fill)
                }
            }
        } else {
            if (shaderWidth != width) {
                shaderWidth = width
                track.shader = rainbow(trackStart - h, trackEnd + h)
            }
            canvas.drawRoundRect(rect, h, h, track)
        }

        val x = xOf(fraction)
        fill.color = 0x55000000
        canvas.drawCircle(x, cy + density, thumbR + density, fill)
        fill.color = thumbColor()
        canvas.drawCircle(x, cy, thumbR, fill)
        canvas.drawCircle(x, cy, thumbR - ring.strokeWidth / 2, ring)
    }

    private fun thumbColor(): Int = when (kind) {
        Kind.COLOR -> ColorBar.colorAt(fraction)
        Kind.BACKGROUND -> ColorBar.backgroundAt(fraction)
        Kind.SPEED -> SPEED_ON
    }

    /** Branco no começo e o arco-íris (de 30 em 30°) no resto, igual a [ColorBar.colorAt]. */
    private fun rainbow(x0: Float, x1: Float): Shader {
        val w = ColorBar.WHITE_PART
        val hues = 12
        val colors = mutableListOf(ColorBar.WHITE, ColorBar.WHITE)
        val positions = mutableListOf(0f, (w - 0.005).toFloat())
        for (i in 0..hues) {
            val f = w + i.toDouble() / hues * (1 - w)
            colors += ColorBar.colorAt(f.coerceAtMost(1.0))
            positions += f.toFloat().coerceAtMost(1f)
        }
        return LinearGradient(x0, 0f, x1, 0f, colors.toIntArray(), positions.toFloatArray(), Shader.TileMode.CLAMP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (!dragging) {
                    if (dx > slop && dx > dy) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        return true
                    }
                }
                move(e.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) move(e.x) // toque sem arrastar: pula para lá
                finish()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) finish() else dragging = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(x: Float) {
        val f = fractionAt(x)
        if (f == fraction && dragging) return
        fraction = f
        invalidate()
        onChange(f)
    }

    private fun finish() {
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        performClick()
        onCommit(fraction)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val TRACK_OFF = 0xFF4A5A60.toInt()
        val SPEED_ON = 0xFF5EE6D8.toInt()
        val TICK = 0xCCFFFFFF.toInt()
    }
}
