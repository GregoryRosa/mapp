package br.com.mapp.maps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Zone
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.util.GeoPoint as OsmGeoPoint

fun GeoPoint.toOsm(): OsmGeoPoint = OsmGeoPoint(lat, lon)

fun OsmGeoPoint.toCore(): GeoPoint = GeoPoint(latitude, longitude)

/** Utilidades osmdroid compartilhadas pelo editor de zonas e pelo mini mapa do overlay. */
object MapViews {

    /** Centro padrão (Brasil) quando ainda não há posição. */
    val DEFAULT_CENTER = GeoPoint(-15.78, -47.93)
    const val DEFAULT_ZOOM = 4.0
    const val CITY_ZOOM = 14.0
    const val MAX_FRAME_ZOOM = 17.0

    val COLOR_CURRENT: Int = 0xFF1E88E5.toInt()
    val COLOR_PICKUP: Int = 0xFF2E7D32.toInt()
    val COLOR_DROPOFF: Int = 0xFFC62828.toInt()
    val COLOR_DRAFT: Int = 0xFFFF6F00.toInt()

    /**
     * Cria um MapView OSM (MAPNIK, sem chave de API).
     * [interactive] = false: mapa estático (mini mapa), que consome os toques sem mover.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun create(context: Context, interactive: Boolean = true): MapView = MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setTilesScaledToDpi(true)
        setMultiTouchControls(interactive)
        zoomController.setVisibility(
            if (interactive) CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
            else CustomZoomButtonsController.Visibility.NEVER,
        )
        setMinZoomLevel(3.0)
        setMaxZoomLevel(19.0)
        controller.setZoom(DEFAULT_ZOOM)
        controller.setCenter(DEFAULT_CENTER.toOsm())
        if (!interactive) setOnTouchListener { _, _ -> true }
    }

    fun clear(map: MapView) {
        InfoWindow.closeAllInfoWindowsOn(map)
        map.overlays.clear()
    }

    /** Polígono preenchido com a cor da zona (alpha da própria cor) e contorno opaco. */
    fun addZone(map: MapView, zone: Zone): Polygon? {
        if (zone.polygon.size < 3) return null
        val color = zone.colorArgb.toInt()
        val poly = Polygon(map)
        poly.setPoints(zone.polygon.map { it.toOsm() })
        poly.fillPaint.color = color
        poly.outlinePaint.color = opaque(color)
        poly.outlinePaint.strokeWidth = 2f * map.resources.displayMetrics.density
        poly.title = "${zone.name} (${zone.type.label})"
        map.overlays.add(poly)
        return poly
    }

    fun addZones(map: MapView, zones: List<Zone>) {
        zones.forEach { addZone(map, it) }
    }

    /** Prévia do desenho: pontos, linha (2 pontos) ou polígono (3+). */
    fun addDraft(map: MapView, vertices: List<GeoPoint>) {
        if (vertices.isEmpty()) return
        val density = map.resources.displayMetrics.density
        if (vertices.size >= 3) {
            val poly = Polygon(map)
            poly.setPoints(vertices.map { it.toOsm() })
            poly.fillPaint.color = (COLOR_DRAFT and 0x00FFFFFF) or 0x55000000
            poly.outlinePaint.color = COLOR_DRAFT
            poly.outlinePaint.strokeWidth = 3f * density
            map.overlays.add(poly)
        } else if (vertices.size == 2) {
            val line = Polyline(map)
            line.setPoints(vertices.map { it.toOsm() })
            line.outlinePaint.color = COLOR_DRAFT
            line.outlinePaint.strokeWidth = 3f * density
            map.overlays.add(line)
        }
        vertices.forEachIndexed { i, v ->
            addMarker(map, v, label = null, color = COLOR_DRAFT, title = "Vértice ${i + 1}", sizeDp = 14f)
        }
    }

    /** Marcador circular com rótulo opcional ("A", "B"). */
    fun addMarker(
        map: MapView,
        point: GeoPoint,
        label: String?,
        color: Int,
        title: String,
        sizeDp: Float = 26f,
    ): Marker {
        val m = Marker(map)
        m.position = point.toOsm()
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = dotDrawable(map.context, label, color, sizeDp)
        m.title = title
        map.overlays.add(m)
        return m
    }

    fun addCurrentPosition(map: MapView, point: GeoPoint): Marker =
        addMarker(map, point, label = null, color = COLOR_CURRENT, title = "Você está aqui", sizeDp = 18f)

    fun addPickup(map: MapView, point: GeoPoint): Marker =
        addMarker(map, point, label = "A", color = COLOR_PICKUP, title = "Coleta")

    fun addDropoff(map: MapView, point: GeoPoint): Marker =
        addMarker(map, point, label = "B", color = COLOR_DROPOFF, title = "Destino")

    /** Desenha zonas + posição atual + A + B e enquadra os pontos. Usado pelo mini mapa. Devolve o marcador da posição. */
    fun renderOffer(
        map: MapView,
        zones: List<Zone>,
        current: GeoPoint?,
        pickup: GeoPoint?,
        dropoff: GeoPoint?,
        marginDp: Int = 28,
    ): Marker? {
        clear(map)
        addZones(map, zones)
        val here = current?.let { addCurrentPosition(map, it) }
        pickup?.let { addPickup(map, it) }
        dropoff?.let { addDropoff(map, it) }
        map.invalidate()
        frame(map, listOfNotNull(current, pickup, dropoff), marginDp)
        return here
    }

    /**
     * Enquadra todos os pontos com margem. Se o mapa ainda não passou por layout,
     * espera o primeiro layout (zoomToBoundingBox precisa do tamanho da view).
     */
    fun frame(map: MapView, points: List<GeoPoint>, marginDp: Int = 32, maxZoom: Double = MAX_FRAME_ZOOM) {
        if (points.isEmpty()) return
        val doFrame = {
            val distinct = points.distinct()
            if (distinct.size == 1) {
                map.controller.setZoom(15.0.coerceAtMost(maxZoom))
                map.controller.setCenter(distinct.first().toOsm())
            } else {
                val box = BoundingBox.fromGeoPoints(distinct.map { it.toOsm() })
                val margin = (marginDp * map.resources.displayMetrics.density).toInt()
                map.zoomToBoundingBox(box, false, margin)
                if (map.zoomLevelDouble > maxZoom) map.controller.setZoom(maxZoom)
            }
            map.invalidate()
        }
        if (map.isLayoutOccurred && map.width > 0 && map.height > 0) {
            doFrame()
        } else {
            map.addOnFirstLayoutListener { _, _, _, _, _ -> doFrame() }
        }
    }

    fun centerOn(map: MapView, point: GeoPoint, zoom: Double = CITY_ZOOM) {
        map.controller.setZoom(zoom)
        map.controller.setCenter(point.toOsm())
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun dotDrawable(context: Context, label: String?, color: Int, sizeDp: Float): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(r, r, r, paint)
        paint.color = opaque(color)
        canvas.drawCircle(r, r, r - 2.5f * density, paint)
        if (label != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = size * 0.55f
            }
            canvas.drawText(label, r, r - (text.ascent() + text.descent()) / 2f, text)
        }
        return BitmapDrawable(context.resources, bmp)
    }
}
