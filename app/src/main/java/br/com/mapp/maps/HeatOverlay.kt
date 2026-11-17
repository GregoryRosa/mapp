package br.com.mapp.maps

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import br.com.mapp.core.heat.HeatCell
import br.com.mapp.core.heat.HeatMetric
import br.com.mapp.core.heat.HeatPalette
import br.com.mapp.core.heat.HeatScale
import br.com.mapp.core.heat.HeatZone
import br.com.mapp.core.model.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Camada do mapa de calor: pinta os hexágonos da grade e contorna as zonas detectadas.
 *
 * Desenha direto no canvas (um caminho de 6 lados por hexágono) em vez de criar um polígono do
 * osmdroid para cada um: são centenas deles a cada mudança de zoom. A mancha fica esfumaçada sem
 * sombra nenhuma: a cor vem de um campo suavizado e a transparência cresce do miolo para a borda,
 * então hexágonos vizinhos se misturam e a mancha se apaga nas pontas.
 */
class HeatOverlay(private val density: Float) : Overlay() {

    var cells: List<HeatCell> = emptyList()
    var zones: List<HeatZone> = emptyList()
    var scale: HeatScale? = null
    var metric: HeatMetric = HeatMetric.CALLS

    /** Cor por modalidade (métrica Modalidade); vazio = usa a escala de cor. */
    var categoryColors: Map<String, Long> = emptyMap()

    /** Zona que o motorista tocou (contorno mais grosso). */
    var highlighted: HeatZone? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val zoneBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF5EE6D8.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12f * density
    }
    private val labelOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12f * density
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val reuse = Point()
    private val path = Path()

    override fun draw(canvas: Canvas, pj: Projection) {
        val s = scale
        cells.forEach { cell ->
            val color = colorOf(cell, s) ?: return@forEach
            if (!shape(pj, cell.polygon, SHRINK)) return@forEach
            fill.color = color
            canvas.drawPath(path, fill)
        }
        zones.forEach { zone ->
            if (shape(pj, zone.polygon, 1f)) {
                zoneBorder.strokeWidth = if (zone === highlighted) 3.5f * density else 2f * density
                canvas.drawPath(path, zoneBorder)
            }
        }
        // Rótulos por último, para ficarem legíveis por cima de tudo.
        zones.forEach { zone -> drawLabel(canvas, pj, zone.center, "${zone.count}") }
    }

    /**
     * Monta o caminho do polígono em [path]. [shrink] encolhe a figura em volta do centro dela, para
     * os hexágonos ficarem separados como no mapa da 99 em vez de virar um bloco só.
     * Devolve false quando a figura fica pequena demais para valer o traço.
     */
    private fun shape(pj: Projection, polygon: List<GeoPoint>, shrink: Float): Boolean {
        if (polygon.size < 3) return false
        path.rewind()
        var cx = 0f
        var cy = 0f
        val xs = FloatArray(polygon.size)
        val ys = FloatArray(polygon.size)
        polygon.forEachIndexed { i, p ->
            pj.toPixels(p.toOsm(), reuse)
            xs[i] = reuse.x.toFloat()
            ys[i] = reuse.y.toFloat()
            cx += xs[i]
            cy += ys[i]
        }
        cx /= polygon.size
        cy /= polygon.size
        var minX = xs[0]
        var maxX = xs[0]
        for (x in xs) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        if (maxX - minX < 2f) return false
        for (i in polygon.indices) {
            val x = cx + (xs[i] - cx) * shrink
            val y = cy + (ys[i] - cy) * shrink
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return true
    }

    /** Nº de chamadas no meio da zona. */
    private fun drawLabel(canvas: Canvas, pj: Projection, at: GeoPoint, text: String) {
        pj.toPixels(at.toOsm(), reuse)
        val x = reuse.x.toFloat()
        val y = reuse.y.toFloat()
        canvas.drawText(text, x, y, labelOutline)
        canvas.drawText(text, x, y, labelText)
    }

    private fun colorOf(cell: HeatCell, scale: HeatScale?): Int? {
        val fraction = scale?.fraction(cell, metric) ?: return null
        if (metric == HeatMetric.CATEGORY) {
            val name = cell.topCategory ?: return null
            val color = categoryColors[name] ?: return null
            return HeatPalette.categoryColor(color, fraction).toInt()
        }
        return HeatPalette.color(metric, fraction).toInt()
    }

    private companion object {
        /** Hexágono desenhado um pouco menor que o lugar dele: fica o vão fino entre eles. */
        const val SHRINK = 0.94f
    }
}
