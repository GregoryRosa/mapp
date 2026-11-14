package br.com.mapp.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Zone
import br.com.mapp.core.notify.MapFrame
import br.com.mapp.core.notify.RidePoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Mapa desenhado num Bitmap, sem MapView na tela (para a notificação da corrida aceita): tiles OSM
 * do retângulo + zonas + linhas busca/viagem + marcadores (você, A = coleta, B = destino).
 * Usa o mesmo cache de tiles do mini mapa; só baixa o que falta.
 */
object MapSnapshots {

    private const val TAG = "MApp/MapSnapshot"
    private const val MIN_ZOOM = 3.0
    /** Fundo enquanto um tile não chega (cor de terra do OSM). */
    private const val LAND = 0xFFF2EFE9.toInt()
    private const val ROUTE = 0xFF37474F.toInt()

    /**
     * Desenha [frame] em [widthPx]×[heightPx] (pixels de tela: os tiles saem no tamanho do mini mapa).
     * Espera os tiles até [timeoutMs] e desenha o que tiver chegado; null se nenhum tile veio
     * (sem internet e sem cache) — um mapa todo cinza não ajuda.
     */
    suspend fun render(
        context: Context,
        frame: MapFrame,
        widthPx: Int,
        heightPx: Int,
        points: RidePoints,
        zones: List<Zone>,
        timeoutMs: Long,
    ): Bitmap? = withContext(Dispatchers.Main) {
        val app = context.applicationContext
        val density = app.resources.displayMetrics.density
        // Mesmo tamanho de tile do MapView com setTilesScaledToDpi (é global no osmdroid).
        val tileSize = (256 * density).toInt()
        if (TileSystem.getTileSize() != tileSize) TileSystem.setTileSize(tileSize)

        val box = BoundingBox(frame.north, frame.east, frame.south, frame.west)
        var zoom = MapView.getTileSystem().getBoundingBoxZoom(box, widthPx, heightPx)
        if (zoom.isNaN() || zoom.isInfinite() || zoom == Double.MIN_VALUE) zoom = MapViews.MAX_FRAME_ZOOM
        zoom = zoom.coerceIn(MIN_ZOOM, MapViews.MAX_FRAME_ZOOM)
        val projection = Projection(
            zoom, widthPx, heightPx, OsmGeoPoint(frame.centerLat, frame.centerLon),
            0f, false, false, 0, 0,
        )

        val provider = MapTileProviderBasic(app, TileSourceFactory.MAPNIK)
        val tiles = TilesOverlay(provider, app, false, false)
        tiles.setLoadingBackgroundColor(LAND)
        tiles.setLoadingLineColor(LAND)
        val arrived = Channel<Unit>(Channel.CONFLATED)
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                arrived.trySend(Unit) // tile baixado (ou falhou): confere de novo
            }
        }
        provider.tileRequestCompleteHandlers.add(handler)
        try {
            val viewPort = projection.getMercatorViewPort(null)
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    // Sem canvas: só pede os tiles e conta o que já está pronto.
                    tiles.drawTiles(null, projection, projection.zoomLevel, viewPort)
                    val s = tiles.tileStates
                    if (s.notFound == 0 && s.scaled == 0) break
                    arrived.receive()
                }
            }
            tiles.drawTiles(null, projection, projection.zoomLevel, viewPort)
            val s = tiles.tileStates
            if (s.upToDate + s.expired + s.scaled == 0) {
                Log.w(TAG, "Nenhum tile carregou ($s)")
                return@withContext null
            }
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(LAND)
            tiles.drawTiles(canvas, projection, projection.zoomLevel, viewPort)
            drawRoute(canvas, projection, density, points, zones)
            drawAttribution(canvas, density, widthPx, heightPx)
            bmp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao desenhar o mapa", e)
            null
        } finally {
            provider.tileRequestCompleteHandlers.remove(handler)
            handler.removeCallbacksAndMessages(null)
            provider.detach()
            arrived.close()
        }
    }

    private fun drawRoute(canvas: Canvas, projection: Projection, density: Float, points: RidePoints, zones: List<Zone>) {
        fun px(p: GeoPoint): PointF {
            val pt = projection.toPixels(OsmGeoPoint(p.lat, p.lon), null)
            return PointF(pt.x.toFloat(), pt.y.toFloat())
        }

        // Zonas desenhadas pelo usuário (mesmas cores do mini mapa).
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        zones.filter { it.polygon.size >= 3 }.forEach { zone ->
            val path = Path()
            zone.polygon.forEachIndexed { i, g ->
                val p = px(g)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            val color = zone.colorArgb.toInt()
            fill.color = color
            outline.color = color or 0xFF000000.toInt()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        }

        val here = points.here?.let(::px)
        val pickup = points.pickup?.let(::px)
        val dropoff = points.dropoff?.let(::px)

        // Linhas retas só para mostrar a ordem (não é o trajeto das ruas): busca tracejada, viagem cheia.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3f * density
        }
        if (here != null && pickup != null) {
            line.color = MapViews.COLOR_CURRENT
            line.pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
            canvas.drawLine(here.x, here.y, pickup.x, pickup.y, line)
        }
        if (pickup != null && dropoff != null) {
            line.color = ROUTE
            line.pathEffect = null
            canvas.drawLine(pickup.x, pickup.y, dropoff.x, dropoff.y, line)
        }

        here?.let { dot(canvas, it, MapViews.COLOR_CURRENT, 9f * density, null, density) }
        pickup?.let { dot(canvas, it, MapViews.COLOR_PICKUP, 13f * density, "A", density) }
        dropoff?.let { dot(canvas, it, MapViews.COLOR_DROPOFF, 13f * density, "B", density) }
    }

    /** Marcador redondo com borda branca e letra (igual ao do mini mapa). */
    private fun dot(canvas: Canvas, at: PointF, color: Int, radius: Float, label: String?, density: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x55000000
        canvas.drawCircle(at.x, at.y + 1.5f * density, radius, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(at.x, at.y, radius, paint)
        paint.color = color or 0xFF000000.toInt()
        canvas.drawCircle(at.x, at.y, radius - 2.5f * density, paint)
        if (label != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = radius * 1.1f
            }
            canvas.drawText(label, at.x, at.y - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    /** Crédito exigido pelo OpenStreetMap, no canto de baixo. */
    private fun drawAttribution(canvas: Canvas, density: Float, width: Int, height: Int) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            textSize = 9f * density
            textAlign = Paint.Align.RIGHT
        }
        val label = "© OpenStreetMap"
        val pad = 3f * density
        val w = text.measureText(label)
        val bg = Paint().apply { color = 0xB3FFFFFF.toInt() }
        canvas.drawRect(width - w - 2 * pad, height - text.textSize - 2 * pad, width.toFloat(), height.toFloat(), bg)
        canvas.drawText(label, width - pad, height - pad - text.descent(), text)
    }
}
