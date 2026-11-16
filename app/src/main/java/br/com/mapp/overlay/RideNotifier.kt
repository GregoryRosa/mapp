package br.com.mapp.overlay

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.mapp.R
import br.com.mapp.core.card.CardFilters
import br.com.mapp.core.geo.PhotoView
import br.com.mapp.core.geo.SatelliteShot
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Zone
import br.com.mapp.core.notify.RideNotice
import br.com.mapp.core.notify.RideNotices
import br.com.mapp.core.notify.RidePoints
import br.com.mapp.core.route.RideRoute
import br.com.mapp.core.route.RouteStop
import br.com.mapp.maps.MapSnapshots
import br.com.mapp.maps.MapViews
import br.com.mapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Notificação no celular a cada corrida aceita. Fechada: tipo, valor e busca → viagem. Expandida:
 * mapa (você, coleta A, destino B, zonas) + fotos da rua de A e B ao lado (Street View, as mesmas
 * do Card — vêm do cache, sem pedir de novo) + R$/km, R$/h, lucro, total, chegada, endereços e
 * passageiro. Sai na hora só com o texto; mapa e fotos entram quando chegam (mesma notificação).
 * Uma por vez: a corrida aceita seguinte substitui. Some sozinha depois da duração prevista.
 */
class RideNotifier(context: Context, private val scope: CoroutineScope) {

    private val context = context.applicationContext

    private enum class MapState { NONE, LOADING, READY, FAILED }

    /** Identidade da oferta notificada (app + 1ª leitura). */
    private var key: Any? = null
    private var offer: EvaluatedOffer? = null
    private var category: String? = null
    private var passenger: String? = null
    private var onBoard = false
    private var acceptedAt = 0L
    private var here: GeoPoint? = null
    private var zones: List<Zone> = emptyList()

    private var points: RidePoints = RidePoints(null, null, null)
    private var map: Bitmap? = null
    private var mapState = MapState.NONE
    private var mapJob: Job? = null

    /** Fotos da rua: coleta e destino (letras do mapa) e o quadro pronto de cada uma. */
    private var photoTargets: List<RouteStop> = emptyList()
    private var photoMode = PhotoView.STREET
    private val photoTiles = HashMap<String, Bitmap>()
    private var photoJob: Job? = null
    private var photoVersion = 0

    /** Último texto/estado notificado: republicação igual não notifica de novo. */
    private var posted: Triple<RideNotice, MapState, Int>? = null

    /** Corrida aceita: notifica já com o texto e começa a desenhar o mapa. */
    fun accepted(
        key: Any,
        offer: EvaluatedOffer,
        category: String?,
        passenger: String?,
        onBoard: Boolean,
        here: GeoPoint?,
        zones: List<Zone>,
    ) {
        this.key = key
        this.offer = offer
        this.category = category
        this.passenger = passenger
        this.onBoard = onBoard
        this.acceptedAt = System.currentTimeMillis()
        this.here = here
        this.zones = zones
        posted = null
        refreshPhotos(force = true)
        refreshMap(force = true)
        post()
    }

    /**
     * A mesma oferta chegou completada (endereço localizado depois, tempo lido noutro quadro):
     * atualiza o texto e, se a coleta/destino mudaram, o mapa.
     */
    fun updated(key: Any, offer: EvaluatedOffer, category: String?) {
        if (key != this.key) return
        this.offer = offer
        category?.let { this.category = it }
        refreshPhotos(force = false)
        refreshMap(force = false)
        post()
    }

    /** Corrida desmarcada (cancelada, ↶ desfazer): tira a notificação dela. */
    fun cancel(key: Any) {
        if (key != this.key) return
        mapJob?.cancel()
        mapJob = null
        photoJob?.cancel()
        photoJob = null
        photoTargets = emptyList()
        photoTiles.clear()
        this.key = null
        offer = null
        map = null
        posted = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun refreshMap(force: Boolean) {
        val o = offer ?: return
        val next = RideNotices.points(here, o.pickup, o.dropoff, o.stops)
        if (!force && next.pickup == points.pickup && next.dropoff == points.dropoff && next.stops == points.stops) return
        points = next
        mapJob?.cancel()
        map = null
        // Mapa desligado no menu Card → Notificação: nem baixa os tiles.
        val frame = if (OverlayPrefs.noticeLayout(context).showMap) RideNotices.frame(next.all) else null
        if (frame == null) {
            mapState = MapState.NONE
            return
        }
        mapState = MapState.LOADING
        val dm = context.resources.displayMetrics
        val full = contentWidthPx()
        // Com as fotos ao lado, o mapa fica com metade da largura (desenhado já nesse tamanho).
        val width = if (photoTargets.isEmpty()) full else ((full - PHOTO_GAP_DP * dm.density) / 2).toInt()
        val height = (MAP_HEIGHT_DP * dm.density).toInt()
        val forKey = key
        // A, B, C…: coleta, paradas no caminho e destino, com as letras mantidas mesmo sem ponto.
        val route = RideRoute.of(o.raw, next.pickup, next.stops, next.dropoff)
        mapJob = scope.launch {
            val drawn = MapSnapshots.render(context, frame, width, height, next.here, route, zones, TILE_TIMEOUT_MS)
            if (forKey != key) return@launch
            map = drawn?.let { compact(it) }
            mapState = if (map != null) MapState.READY else MapState.FAILED
            post()
        }
    }

    private fun contentWidthPx(): Int {
        val dm = context.resources.displayMetrics
        return (min(dm.widthPixels, dm.heightPixels) - CONTENT_MARGIN_DP * dm.density).toInt().coerceAtLeast(MIN_WIDTH_PX)
    }

    /**
     * Fotos da coleta e do destino — foto da rua ou satélite, como escolhido em Card → Notificação
     * (as mesmas do Card: [StreetViewPhotos] guarda na memória, então normalmente não pede nada ao
     * Google de novo). Ligadas em Card → Notificação e com a chave colada.
     */
    private fun refreshPhotos(force: Boolean) {
        val o = offer ?: return
        val layout = OverlayPrefs.noticeLayout(context)
        val on = layout.showPhotos && OverlayPrefs.streetViewEnabled(context)
        val picked = if (on) StreetView.pick(RideRoute.of(o)) else emptyList()
        val targets = listOfNotNull(picked.firstOrNull(), picked.lastOrNull()).distinct()
        val mode = layout.photoView
        if (!force && targets == photoTargets && mode == photoMode) return
        photoTargets = targets
        photoMode = mode
        photoJob?.cancel()
        photoTiles.clear()
        photoVersion++
        if (targets.isEmpty()) return
        val (w, h) = photoTileSize()
        targets.forEach { stop ->
            val msg = when {
                stop.point != null -> if (mode == PhotoView.SATELLITE) "Carregando satélite…" else "Carregando foto…"
                stop.address != null -> "Localizando endereço…"
                else -> "Endereço não lido"
            }
            photoTiles[stop.letter] = placeholderTile(stop, msg, w, h)
        }
        val forKey = key
        photoJob = scope.launch {
            targets.forEach { stop ->
                if (stop.point == null) return@forEach
                val result = StreetViewPhotos.load(context, stop, mode, SatelliteShot.STRIP)
                if (forKey != key) return@launch
                photoTiles[stop.letter] = when (result) {
                    is StreetViewPhotos.Result.Photo -> photoTile(stop, result, w, h)
                    is StreetViewPhotos.Result.Missing -> placeholderTile(stop, result.message, w, h)
                }
                photoVersion++
                post()
            }
        }
    }

    /**
     * Tamanho de cada quadro em pixels: metade da largura (ao lado do mapa) ou a largura toda (mapa
     * desligado), metade da altura do mapa. No máximo 2 px por dp: a notificação vai por IPC.
     */
    private fun photoTileSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val withMap = OverlayPrefs.noticeLayout(context).showMap
        val widthDp = contentWidthPx() / dm.density / (if (withMap) 2 else 1) - PHOTO_GAP_DP
        val heightDp = (MAP_HEIGHT_DP - PHOTO_GAP_DP) / 2f
        val scale = min(dm.density, 2f)
        return (widthDp * scale).toInt().coerceAtLeast(1) to (heightDp * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Foto da rua cortada no tamanho do quadro (centro, onde fica a fachada) + letra e crédito
     * desenhados. Satélite: imagem inteira, sem corte e sem crédito por cima (o logo e os créditos do
     * Google estão dentro dela).
     */
    private fun photoTile(stop: RouteStop, photo: StreetViewPhotos.Result.Photo, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        val src = photo.bitmap
        if (photo.satellite) {
            canvas.drawColor(PHOTO_BG)
            val fit = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = src.width * fit
            val dh = src.height * fit
            val dest = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(src, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            drawBadge(canvas, stop, h)
            return out
        }
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt().coerceIn(1, src.width)
        val ch = (h / scale).toInt().coerceIn(1, src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        canvas.drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        drawBadge(canvas, stop, h)
        val credit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            textSize = h * 0.13f
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        canvas.drawText(photo.credit, w - h * 0.06f, h - h * 0.07f, credit)
        return out
    }

    /** Quadro sem foto (carregando, sem foto aqui, sem internet…) com a letra do ponto. */
    private fun placeholderTile(stop: RouteStop, message: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        canvas.drawColor(PHOTO_BG)
        drawBadge(canvas, stop, h)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDBDBD.toInt()
            textSize = h * 0.17f
            textAlign = Paint.Align.CENTER
        }
        // Texto à direita da letra, centralizado no espaço que sobra.
        val start = h * 0.5f
        canvas.drawText(message, start + (w - start) / 2f, h / 2f + text.textSize / 3f, text)
        return out
    }

    private fun drawBadge(canvas: Canvas, stop: RouteStop, h: Int) {
        val r = h * 0.17f
        val cx = h * 0.06f + r
        val cy = h * 0.06f + r
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapViews.colorOf(stop.kind) }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = r * 0.12f
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = r * 1.2f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(stop.letter, cx, cy + letter.textSize / 3f, letter)
    }

    /**
     * Bitmap pequeno o bastante para a notificação (vai por IPC para o sistema): RGB_565 e, em tela
     * muito grande, reduzido.
     */
    private fun compact(bmp: Bitmap): Bitmap {
        val pixels = bmp.width.toLong() * bmp.height
        val scaled = if (pixels > MAX_MAP_PIXELS) {
            val f = sqrt(MAX_MAP_PIXELS.toDouble() / pixels)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else {
            bmp
        }
        val small = scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
        if (scaled !== bmp && small !== scaled) scaled.recycle()
        if (small !== bmp) bmp.recycle()
        return small
    }

    private fun post() {
        val o = offer ?: return
        if (!canNotify()) return
        val layout = OverlayPrefs.noticeLayout(context)
        val text = RideNotices.of(o, category, passenger, acceptedAt, ZoneId.systemDefault(), onBoard, points, layout)
        val state = Triple(text, mapState, photoVersion)
        if (posted == state) return
        posted = state

        val note = if (!text.showMap) null else when (mapState) {
            MapState.LOADING -> "Carregando mapa…"
            MapState.FAILED -> "Mapa indisponível agora (sem internet?)"
            else -> null
        } ?: text.mapNote
        val summaryVisibility = if (text.summary.isEmpty()) View.GONE else View.VISIBLE

        val small = RemoteViews(context.packageName, R.layout.notification_ride_small).apply {
            setTextViewText(R.id.ride_title, text.title)
            setTextViewText(R.id.ride_summary, text.summary)
            setViewVisibility(R.id.ride_summary, summaryVisibility)
        }
        val big = RemoteViews(context.packageName, R.layout.notification_ride_big).apply {
            setTextViewText(R.id.ride_title, text.title)
            setTextViewText(R.id.ride_summary, text.summary)
            setViewVisibility(R.id.ride_summary, summaryVisibility)
            val bmp = map?.takeIf { text.showMap }
            val tiles = photoTargets.mapNotNull { photoTiles[it.letter] }
            // Com fotos, o espaço do mapa fica reservado enquanto ele carrega (as fotos não pulam de lugar).
            val mapSlot = bmp != null || (tiles.isNotEmpty() && text.showMap && mapState == MapState.LOADING)
            if (bmp != null) setImageViewBitmap(R.id.ride_map, bmp)
            setViewVisibility(R.id.ride_map, if (mapSlot) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.ride_photos, if (tiles.isEmpty()) View.GONE else View.VISIBLE)
            tiles.getOrNull(0)?.let { setImageViewBitmap(R.id.ride_photo_a, it) }
            val second = tiles.getOrNull(1)
            if (second != null) setImageViewBitmap(R.id.ride_photo_b, second)
            setViewVisibility(R.id.ride_photo_b, if (second != null) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.ride_media, if (mapSlot || tiles.isNotEmpty()) View.VISIBLE else View.GONE)
            if (note != null) {
                setTextViewText(R.id.ride_map_note, note)
                setViewVisibility(R.id.ride_map_note, View.VISIBLE)
            } else {
                setViewVisibility(R.id.ride_map_note, View.GONE)
            }
            LINE_IDS.forEachIndexed { i, id ->
                val line = text.details.getOrNull(i)
                if (line != null) {
                    setTextViewText(id, line)
                    setViewVisibility(id, View.VISIBLE)
                } else {
                    setViewVisibility(id, View.GONE)
                }
            }
        }

        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val expectedMin = o.metrics.totalMin ?: DEFAULT_RIDE_MIN
        // Cor da notificação: a do filtro do Card que pinta (Card → Filtros) > roxo da parada > MApp.
        val filterColor = CardFilters.paintColor(CardFilters.matching(OverlayPrefs.cardFilters(context), o))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setColor(filterColor ?: if (text.hasStops) CardHeaderView.STOP_PURPLE else MAPP_TEAL)
            .setContentTitle(text.title)
            .setContentText(text.summary)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setWhen(acceptedAt)
            .setShowWhen(true)
            .setOnlyAlertOnce(true) // o mapa chegando não toca de novo
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setTimeoutAfter((expectedMin + EXTRA_MIN) * 60_000L)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao mostrar a notificação da corrida", e)
        }
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Corrida aceita")
                .setDescription("Rota da corrida aceita; expanda para ver o mapa")
                // O app de corrida já toca no aceite: esta fica na barra, sem som nem vibração.
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build(),
        )
        return true
    }

    private companion object {
        const val TAG = "MApp/RideNotifier"
        const val CHANNEL_ID = "corrida_aceita"
        const val NOTIFICATION_ID = 1002
        const val REQUEST_OPEN = 10
        const val MAP_HEIGHT_DP = 112
        /** Espaço entre o mapa e as fotos e entre as duas fotos. */
        const val PHOTO_GAP_DP = 4
        val PHOTO_BG = 0xFF2C2F33.toInt()
        /** Margens da notificação dos dois lados (o ImageView corta o que sobrar, centralizado). */
        const val CONTENT_MARGIN_DP = 48
        const val MIN_WIDTH_PX = 320
        /** ~1 MB em RGB_565. */
        const val MAX_MAP_PIXELS = 500_000L
        const val TILE_TIMEOUT_MS = 10_000L
        /** Sem duração prevista: some depois de 30 + [EXTRA_MIN] min. */
        const val DEFAULT_RIDE_MIN = 30
        const val EXTRA_MIN = 30
        val MAPP_TEAL = 0xFF5EE6D8.toInt()
        val LINE_IDS = intArrayOf(R.id.ride_line1, R.id.ride_line2, R.id.ride_line3, R.id.ride_line4, R.id.ride_line5)
    }
}
