package br.com.mapp.overlay

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import br.com.mapp.core.media.MediaBlock
import br.com.mapp.core.media.MediaLayout
import br.com.mapp.core.media.MediaPhotos
import br.com.mapp.core.media.MediaPlaced
import br.com.mapp.core.media.MediaSizes
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Notificação no celular a cada corrida aceita. Fechada: tipo, valor e busca → viagem. Expandida:
 * mapa (você, coleta A, destino B, zonas) + fotos de A e do destino (rua ou satélite; as mesmas do
 * Card vêm do cache, sem pedir de novo), num quadro só desenhado no modelo de Card → Notificação →
 * Mapa e fotos ([MediaLayout]) + R$/km, R$/h, lucro, total, chegada, endereços e passageiro. Sai na
 * hora só com o texto; mapa e fotos entram quando chegam (mesma notificação).
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

    /** Onde fica cada quadro (dp) no modelo escolhido; null = sem mapa nem fotos. */
    private var plan: MediaPlan? = null

    /** Fotos da coleta e do destino (letras do mapa) e o quadro pronto de cada uma. */
    private var photoTargets: Map<MediaBlock, RouteStop> = emptyMap()
    private var photoMode = PhotoView.STREET
    private val photoTiles = HashMap<MediaBlock, Bitmap>()
    private var photoJob: Job? = null

    /** Muda a cada mapa/foto/modelo novo: o quadro montado é refeito só quando muda. */
    private var mediaVersion = 0
    private var composed: Pair<Int, Bitmap?>? = null

    /** Último texto/estado notificado: republicação igual não notifica de novo. */
    private var posted: Triple<RideNotice, MapState, Int>? = null

    /**
     * Área de mapa e fotos: [cells] em dp dentro de [widthDp] × [heightDp]; [scale] = px por dp do
     * quadro montado (no máximo [MAX_MEDIA_PIXELS] no total: a notificação vai por IPC).
     */
    private data class MediaPlan(val widthDp: Int, val heightDp: Int, val cells: Map<MediaBlock, MediaPlaced>, val scale: Float)

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
        refreshMedia(force = true)
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
        refreshMedia(force = false)
        post()
    }

    /** Corrida desmarcada (cancelada, ↶ desfazer): tira a notificação dela. */
    fun cancel(key: Any) {
        if (key != this.key) return
        mapJob?.cancel()
        mapJob = null
        photoJob?.cancel()
        photoJob = null
        photoTargets = emptyMap()
        photoTiles.clear()
        this.key = null
        offer = null
        map = null
        plan = null
        composed = null
        posted = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Refaz a área de mapa e fotos: quais quadros existem nesta corrida (mapa ligado e com pontos;
     * fotos ligadas e com a chave), onde cada um fica no modelo escolhido e o que precisa ser
     * desenhado/buscado de novo (pontos, modelo ou modo da foto mudaram).
     */
    private fun refreshMedia(force: Boolean) {
        val o = offer ?: return
        val layout = OverlayPrefs.noticeLayout(context)
        val next = RideNotices.points(here, o.pickup, o.dropoff, o.stops)
        // Mapa desligado no menu Card → Notificação: nem baixa os tiles.
        val frame = if (layout.showMap) RideNotices.frame(next.all) else null
        val photosOn = layout.showPhotos && OverlayPrefs.streetViewEnabled(context)
        val stops = if (photosOn) MediaPhotos.stops(RideRoute.of(o)) else emptyMap()
        val available = stops.keys + listOfNotNull(MediaBlock.MAP.takeIf { frame != null })
        val nextPlan = layout.media.arrange(available)?.let { area ->
            val sizes = MediaSizes.NOTICE
            val dm = context.resources.displayMetrics
            val w = contentWidthDp()
            val h = MediaLayout.height(area, sizes)
            val scale = min(dm.density, sqrt(MAX_MEDIA_PIXELS / (w.toFloat() * h)))
            MediaPlan(w, h, MediaLayout.place(area, w, h, sizes).associateBy { it.block }, scale)
        }
        val pointsChanged = next.pickup != points.pickup || next.dropoff != points.dropoff || next.stops != points.stops
        val planChanged = nextPlan != plan
        points = next
        plan = nextPlan
        if (planChanged) mediaVersion++

        val mapCell = nextPlan?.cells?.get(MediaBlock.MAP)
        if (force || pointsChanged || planChanged) {
            mapJob?.cancel()
            map = null
            if (frame == null || mapCell == null) {
                mapState = MapState.NONE
            } else {
                mapState = MapState.LOADING
                // Desenhado em pixels de tela (tiles do tamanho do mini mapa); reduzido ao montar o quadro.
                val density = context.resources.displayMetrics.density
                val width = (mapCell.width * density).toInt().coerceAtLeast(1)
                val height = (mapCell.height * density).toInt().coerceAtLeast(1)
                val forKey = key
                // A, B, C…: coleta, paradas no caminho e destino, com as letras mantidas mesmo sem ponto.
                val route = RideRoute.of(o.raw, next.pickup, next.stops, next.dropoff)
                mapJob = scope.launch {
                    val drawn = MapSnapshots.render(context, frame, width, height, next.here, route, zones, TILE_TIMEOUT_MS)
                    if (forKey != key) return@launch
                    map = drawn
                    mapState = if (drawn != null) MapState.READY else MapState.FAILED
                    mediaVersion++
                    post()
                }
            }
        }

        val mode = layout.photoView
        val targets = stops.filterKeys { nextPlan?.cells?.containsKey(it) == true }
        if (!force && !planChanged && targets == photoTargets && mode == photoMode) return
        photoTargets = targets
        photoMode = mode
        photoJob?.cancel()
        photoTiles.clear()
        mediaVersion++
        val p = nextPlan ?: return
        if (targets.isEmpty()) return
        targets.forEach { (block, stop) ->
            val (w, h) = tileSize(p, p.cells.getValue(block))
            val unit = badgeUnit(p, h)
            val msg = when {
                stop.point != null -> if (mode == PhotoView.SATELLITE) "Carregando satélite…" else "Carregando foto…"
                stop.address != null -> "Localizando endereço…"
                else -> "Endereço não lido"
            }
            photoTiles[block] = placeholderTile(stop, msg, w, h, unit)
        }
        val forKey = key
        photoJob = scope.launch {
            targets.forEach { (block, stop) ->
                if (stop.point == null) return@forEach
                val cell = p.cells.getValue(block)
                // Satélite no formato do quadro (faixa, quadrado…): a imagem cabe inteira.
                val shot = SatelliteShot.forAspect(cell.width.toDouble() / cell.height)
                val result = StreetViewPhotos.load(context, stop, mode, shot)
                if (forKey != key) return@launch
                val (w, h) = tileSize(p, cell)
                val unit = badgeUnit(p, h)
                photoTiles[block] = when (result) {
                    is StreetViewPhotos.Result.Photo -> photoTile(stop, result, w, h, unit)
                    is StreetViewPhotos.Result.Missing -> placeholderTile(stop, result.message, w, h, unit)
                }
                mediaVersion++
                post()
            }
        }
    }

    /** Largura útil da notificação em dp (o ImageView ajusta a imagem à largura real). */
    private fun contentWidthDp(): Int {
        val dm = context.resources.displayMetrics
        return (min(dm.widthPixels, dm.heightPixels) / dm.density - CONTENT_MARGIN_DP).toInt().coerceAtLeast(MIN_WIDTH_DP)
    }

    /** Tamanho em px de um quadro já na escala do quadro montado. */
    private fun tileSize(p: MediaPlan, cell: MediaPlaced): Pair<Int, Int> =
        (cell.width * p.scale).toInt().coerceAtLeast(1) to (cell.height * p.scale).toInt().coerceAtLeast(1)

    /** Tamanho de referência da letra e dos textos do quadro: a altura, até ~54 dp (o quadro de antes). */
    private fun badgeUnit(p: MediaPlan, heightPx: Int): Int = min(heightPx, (BADGE_REF_DP * p.scale).toInt()).coerceAtLeast(1)

    /**
     * Mapa + fotos num Bitmap só, cada quadro com cantos arredondados no lugar do modelo (o espaço
     * entre eles fica transparente). Refeito só quando um mapa/foto/modelo muda.
     */
    private fun composedMedia(): Bitmap? {
        composed?.let { (version, bmp) -> if (version == mediaVersion) return bmp }
        val p = plan
        val out = if (p == null) null else compose(p)
        composed = mediaVersion to out
        return out
    }

    private fun compose(p: MediaPlan): Bitmap {
        val s = p.scale
        val out = Bitmap.createBitmap((p.widthDp * s).toInt().coerceAtLeast(1), (p.heightDp * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = CORNER_DP * s
        p.cells.forEach { (block, cell) ->
            val dest = RectF(cell.x * s, cell.y * s, (cell.x + cell.width) * s, (cell.y + cell.height) * s)
            val src = if (block == MediaBlock.MAP) map else photoTiles[block]
            if (src != null) {
                drawRounded(canvas, src, dest, radius)
            } else {
                val msg = when {
                    block != MediaBlock.MAP -> ""
                    mapState == MapState.FAILED -> "Mapa indisponível"
                    else -> "Carregando mapa…"
                }
                drawEmpty(canvas, dest, radius, msg)
            }
        }
        return out
    }

    /** [src] cortado no centro para preencher [dest] (os quadros já vêm quase no formato certo). */
    private fun drawRounded(canvas: Canvas, src: Bitmap, dest: RectF, radius: Float) {
        val f = max(dest.width() / src.width, dest.height() / src.height)
        val m = Matrix().apply {
            setScale(f, f)
            postTranslate(dest.left + (dest.width() - src.width * f) / 2f, dest.top + (dest.height() - src.height * f) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        }
        canvas.drawRoundRect(dest, radius, radius, paint)
    }

    private fun drawEmpty(canvas: Canvas, dest: RectF, radius: Float, message: String) {
        canvas.drawRoundRect(dest, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PHOTO_BG })
        if (message.isEmpty()) return
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDBDBD.toInt()
            textSize = min(dest.height() * 0.17f, dest.width() * 0.09f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(message, dest.centerX(), dest.centerY() + text.textSize / 3f, text)
    }

    /**
     * Foto da rua cortada no tamanho do quadro (centro, onde fica a fachada) + letra e crédito
     * desenhados. Satélite: imagem inteira, sem corte e sem crédito por cima (o logo e os créditos do
     * Google estão dentro dela).
     */
    private fun photoTile(stop: RouteStop, photo: StreetViewPhotos.Result.Photo, w: Int, h: Int, unit: Int): Bitmap {
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
            drawBadge(canvas, stop, unit)
            return out
        }
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt().coerceIn(1, src.width)
        val ch = (h / scale).toInt().coerceIn(1, src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        canvas.drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        drawBadge(canvas, stop, unit)
        val credit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            textSize = unit * 0.13f
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        canvas.drawText(photo.credit, w - unit * 0.06f, h - unit * 0.07f, credit)
        return out
    }

    /** Quadro sem foto (carregando, sem foto aqui, sem internet…) com a letra do ponto. */
    private fun placeholderTile(stop: RouteStop, message: String, w: Int, h: Int, unit: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        canvas.drawColor(PHOTO_BG)
        drawBadge(canvas, stop, unit)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDBDBD.toInt()
            textSize = unit * 0.17f
            textAlign = Paint.Align.CENTER
        }
        // Texto à direita da letra, centralizado no espaço que sobra.
        val start = unit * 0.5f
        canvas.drawText(message, start + (w - start) / 2f, h / 2f + text.textSize / 3f, text)
        return out
    }

    /** Letra do ponto no canto; [h] = altura de referência (a letra não cresce em quadro alto). */
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

    private fun post() {
        val o = offer ?: return
        if (!canNotify()) return
        val layout = OverlayPrefs.noticeLayout(context)
        val text = RideNotices.of(o, category, passenger, acceptedAt, ZoneId.systemDefault(), onBoard, points, layout)
        val state = Triple(text, mapState, mediaVersion)
        if (posted == state) return
        posted = state

        val note = if (plan?.cells?.containsKey(MediaBlock.MAP) != true) null else when (mapState) {
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
            // Mapa e fotos num quadro só (os espaços ficam reservados enquanto carregam: nada pula de lugar).
            val media = composedMedia()
            if (media != null) setImageViewBitmap(R.id.ride_media, media)
            setViewVisibility(R.id.ride_media, if (media != null) View.VISIBLE else View.GONE)
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
        /** Altura do quadro A/B de antes: letra e textos não crescem além disso. */
        const val BADGE_REF_DP = 54
        /** Cantos arredondados de cada quadro. */
        const val CORNER_DP = 6f
        val PHOTO_BG = 0xFF2C2F33.toInt()
        /** Margens da notificação dos dois lados (o ImageView ajusta a imagem à largura real). */
        const val CONTENT_MARGIN_DP = 48
        const val MIN_WIDTH_DP = 200
        /** Quadro montado: ~720 KB em ARGB (mapa + fotos juntos; antes eram ~700 KB em 3 imagens). */
        const val MAX_MEDIA_PIXELS = 180_000f
        const val TILE_TIMEOUT_MS = 10_000L
        /** Sem duração prevista: some depois de 30 + [EXTRA_MIN] min. */
        const val DEFAULT_RIDE_MIN = 30
        const val EXTRA_MIN = 30
        val MAPP_TEAL = 0xFF5EE6D8.toInt()
        val LINE_IDS = intArrayOf(R.id.ride_line1, R.id.ride_line2, R.id.ride_line3, R.id.ride_line4, R.id.ride_line5)
    }
}
