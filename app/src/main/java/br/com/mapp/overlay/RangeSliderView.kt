package br.com.mapp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import br.com.mapp.core.filter.FilterCriterion
import br.com.mapp.core.filter.ThresholdRange
import br.com.mapp.core.filter.ThresholdScale
import kotlin.math.abs

/**
 * Slider de faixa do painel do ícone "M" (View comum, sem Compose): trilho vermelho/amarelo/verde
 * (ou verde/amarelo/vermelho na busca) com dois botões e o valor de cada um em cima, igual à tela
 * de Perfis. Arrastar mostra o valor na hora; o perfil só é gravado ao soltar ([onCommit]).
 * Arrasto na horizontal não rola o painel; na vertical, rola.
 */
@SuppressLint("ViewConstructor")
class RangeSliderView(
    context: Context,
    private val criterion: FilterCriterion,
    private val onCommit: (ThresholdRange) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var range: ThresholdRange = criterion.default
    /** Perfil sem este critério: trilho cinza até o motorista mexer. */
    private var off = true
    private var scale: ThresholdScale = criterion.scale.fitting(range.low, range.high)

    private var thumb = -1
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    private val thumbR = 11f * density
    private val trackH = 8f * density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val rect = RectF()

    /** Mostra a faixa do perfil (ignorado enquanto o dedo arrasta, para não pular). */
    fun bind(saved: ThresholdRange?) {
        if (dragging) return
        off = saved == null
        range = saved ?: criterion.default
        scale = criterion.scale.fitting(range.low, range.high)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = labelHeight() + 4 * density + thumbR * 2 + 4 * density
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h.toInt())
    }

    private fun labelHeight(): Float = labelPaint.fontMetrics.let { it.descent - it.ascent }

    // Nada de "left"/"right": colidiria com View.getLeft()/getRight().
    private val trackStart: Float get() = thumbR
    private val trackEnd: Float get() = width - thumbR
    private fun xOf(v: Double) = trackStart + scale.fraction(v).toFloat() * (trackEnd - trackStart)
    private fun valueAt(x: Float) = scale.valueAt(((x - trackStart) / (trackEnd - trackStart)).toDouble())
    private fun values() = listOf(range.low, range.high)

    override fun onDraw(canvas: Canvas) {
        val cy = labelHeight() + 4 * density + thumbR
        val xs = values().map(::xOf)
        val colors = if (off) listOf(OFF, OFF, OFF) else segmentColors()
        val h = trackH / 2
        // Trilho na cor da ponta direita; trechos da esquerda e do meio por cima.
        fill.color = colors[2]
        rect.set(trackStart - h, cy - h, trackEnd + h, cy + h)
        canvas.drawRoundRect(rect, h, h, fill)
        fill.color = colors[1]
        canvas.drawRect(xs[0], cy - h, xs[1], cy + h, fill)
        fill.color = colors[0]
        rect.set(trackStart - h, cy - h, xs[0], cy + h)
        canvas.drawRoundRect(rect, h, h, fill)

        val thumbColors = if (off) listOf(OFF_TEXT, OFF_TEXT) else thumbColors()
        xs.forEachIndexed { i, x ->
            fill.color = 0x55000000
            canvas.drawCircle(x, cy + density, thumbR + density, fill)
            fill.color = WHITE
            canvas.drawCircle(x, cy, thumbR, fill)
            ring.color = thumbColors[i]
            canvas.drawCircle(x, cy, thumbR - 1.5f * density, ring)
        }
        drawLabels(canvas, xs, thumbColors)
    }

    /** Valor em cima de cada botão, sem sair do painel e sem um cobrir o outro. */
    private fun drawLabels(canvas: Canvas, xs: List<Float>, colors: List<Int>) {
        val texts = values().map(criterion::format)
        val widths = texts.map { labelPaint.measureText(it) }
        val pos = xs.mapIndexed { i, x -> (x - widths[i] / 2).coerceIn(0f, (width - widths[i]).coerceAtLeast(0f)) }
            .toMutableList()
        val gap = 8 * density
        val overlap = pos[0] + widths[0] + gap - pos[1]
        if (overlap > 0) {
            pos[0] -= overlap / 2
            pos[1] += overlap / 2
            if (pos[0] < 0) { pos[1] -= pos[0]; pos[0] = 0f }
            val over = pos[1] + widths[1] - width
            if (over > 0) { pos[0] -= over; pos[1] -= over }
        }
        val baseline = -labelPaint.fontMetrics.ascent
        texts.forEachIndexed { i, t ->
            labelPaint.color = colors[i]
            canvas.drawText(t, pos[i], baseline, labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                thumb = pick(e.x)
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
                // Toque sem arrastar: o botão mais perto pula para lá.
                if (!dragging) move(e.x)
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

    private fun pick(x: Float): Int {
        val a = xOf(range.low)
        val b = xOf(range.high)
        return when {
            abs(a - b) < 1f -> if (x < a) 0 else 1 // juntos: o lado do toque decide
            abs(x - a) <= abs(x - b) -> 0
            else -> 1
        }
    }

    private fun move(x: Float) {
        val v = valueAt(x)
        range = if (thumb == 0) range.withLow(v) else range.withHigh(v)
        off = false
        invalidate()
    }

    private fun finish() {
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        performClick()
        onCommit(range)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun segmentColors() =
        if (criterion.higherIsBetter) listOf(RED, YELLOW, GREEN) else listOf(GREEN, YELLOW, RED)

    private fun thumbColors() =
        if (criterion.higherIsBetter) listOf(YELLOW, GREEN) else listOf(GREEN, YELLOW)

    private companion object {
        val RED = 0xFFD24B4B.toInt()
        val YELLOW = 0xFFF2C14E.toInt()
        val GREEN = 0xFF9ACD45.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val OFF = 0xFF4A5A60.toInt()
        val OFF_TEXT = 0xFF9E9E9E.toInt()
    }
}
