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
 * Camada do mapa de calor: pinta os quadradinhos da grade e contorna as zonas detectadas.
 *
 * Desenha direto no canvas (um retângulo por quadradinho) em vez de criar um polígono do osmdroid
 * para cada um: são centenas de quadradinhos a cada mudança de zoom.
 */
class HeatOverlay(private val density: Float) : Overlay() {

    var cells: List<HeatCell> = emptyList()
    var zones: List<HeatZone> = emptyList()
    var scale: HeatScale? = null
    var metric: HeatMetric = HeatMetric.CALLS

    /** Cor por modalidade (métrica Modalidade); vazio = usa a escala de níveis. */
    var categoryColors: Map<String, Long> = emptyMap()

    /** Zona que o motorista tocou (contorno mais grosso). */
    var highlighted: HeatZone? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cellBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * density
        color = 0x33000000
    }
    private val zoneBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
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

    private val sw = Point()
    private val ne = Point()

    override fun draw(canvas: Canvas, pj: Projection) {
        val s = scale
        cells.forEach { cell ->
            val color = colorOf(cell, s) ?: return@forEach
            pj.toPixels(cell.southWest.toOsm(), sw)
            pj.toPixels(cell.northEast.toOsm(), ne)
            val left = minOf(sw.x, ne.x).toFloat()
            val right = maxOf(sw.x, ne.x).toFloat()
            val top = minOf(sw.y, ne.y).toFloat()
            val bottom = maxOf(sw.y, ne.y).toFloat()
            if (right - left < 1.5f || bottom - top < 1.5f) return@forEach
            fill.color = color
            canvas.drawRect(left, top, right, bottom, fill)
            if (right - left > 14f * density) canvas.drawRect(left, top, right, bottom, cellBorder)
        }
        zones.forEach { zone -> drawZone(canvas, pj, zone) }
        // Rótulos por último, para ficarem legíveis por cima de tudo.
        zones.forEach { zone -> drawLabel(canvas, pj, zone.center, "${zone.count}") }
    }

    private fun drawZone(canvas: Canvas, pj: Projection, zone: HeatZone) {
        if (zone.polygon.size < 3) return
        val path = Path()
        zone.polygon.forEachIndexed { i, p ->
            pj.toPixels(p.toOsm(), sw)
            if (i == 0) {
                path.moveTo(sw.x.toFloat(), sw.y.toFloat())
            } else {
                path.lineTo(sw.x.toFloat(), sw.y.toFloat())
            }
        }
        path.close()
        zoneBorder.strokeWidth = if (zone === highlighted) 4f * density else 2.5f * density
        canvas.drawPath(path, zoneBorder)
    }

    /** Nº de chamadas no meio da zona. */
    private fun drawLabel(canvas: Canvas, pj: Projection, at: GeoPoint, text: String) {
        pj.toPixels(at.toOsm(), sw)
        val x = sw.x.toFloat()
        val y = sw.y.toFloat()
        canvas.drawText(text, x, y, labelOutline)
        canvas.drawText(text, x, y, labelText)
    }

    private fun colorOf(cell: HeatCell, scale: HeatScale?): Int? {
        if (metric == HeatMetric.CATEGORY) {
            val name = cell.topCategory ?: return null
            val color = categoryColors[name] ?: return null
            return HeatPalette.translucent(color).toInt()
        }
        val level = scale?.level(cell, metric) ?: return null
        return HeatPalette.color(metric, level).toInt()
    }
}
