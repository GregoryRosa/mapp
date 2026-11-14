package br.com.mapp.overlay

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.mapp.R
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Zone
import br.com.mapp.core.notify.RideNotice
import br.com.mapp.core.notify.RideNotices
import br.com.mapp.core.notify.RidePoints
import br.com.mapp.maps.MapSnapshots
import br.com.mapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Notificação no celular a cada corrida aceita. Fechada: tipo, valor e busca → viagem. Expandida:
 * mapa (você, coleta A, destino B, zonas) + R$/km, R$/h, lucro, total, chegada, endereços e
 * passageiro. Sai na hora só com o texto; o mapa entra quando os tiles chegam (mesma notificação).
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
    /** Último texto/estado notificado: republicação igual não notifica de novo. */
    private var posted: Pair<RideNotice, MapState>? = null

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
        refreshMap(force = false)
        post()
    }

    /** Corrida desmarcada (cancelada, ↶ desfazer): tira a notificação dela. */
    fun cancel(key: Any) {
        if (key != this.key) return
        mapJob?.cancel()
        mapJob = null
        this.key = null
        offer = null
        map = null
        posted = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun refreshMap(force: Boolean) {
        val o = offer ?: return
        val next = RideNotices.points(here, o.pickup, o.dropoff)
        if (!force && next.pickup == points.pickup && next.dropoff == points.dropoff) return
        points = next
        mapJob?.cancel()
        map = null
        val frame = RideNotices.frame(next.all)
        if (frame == null) {
            mapState = MapState.NONE
            return
        }
        mapState = MapState.LOADING
        val dm = context.resources.displayMetrics
        val width = (min(dm.widthPixels, dm.heightPixels) - CONTENT_MARGIN_DP * dm.density).toInt().coerceAtLeast(MIN_WIDTH_PX)
        val height = (MAP_HEIGHT_DP * dm.density).toInt()
        val forKey = key
        mapJob = scope.launch {
            val drawn = MapSnapshots.render(context, frame, width, height, next, zones, TILE_TIMEOUT_MS)
            if (forKey != key) return@launch
            map = drawn?.let { compact(it) }
            mapState = if (map != null) MapState.READY else MapState.FAILED
            post()
        }
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
        val text = RideNotices.of(o, category, passenger, acceptedAt, ZoneId.systemDefault(), onBoard, points)
        if (posted == text to mapState) return
        posted = text to mapState

        val note = when (mapState) {
            MapState.LOADING -> "Carregando mapa…"
            MapState.FAILED -> "Mapa indisponível agora (sem internet?)"
            else -> null
        } ?: text.mapNote

        val small = RemoteViews(context.packageName, R.layout.notification_ride_small).apply {
            setTextViewText(R.id.ride_title, text.title)
            setTextViewText(R.id.ride_summary, text.summary)
        }
        val big = RemoteViews(context.packageName, R.layout.notification_ride_big).apply {
            setTextViewText(R.id.ride_title, text.title)
            setTextViewText(R.id.ride_summary, text.summary)
            val bmp = map
            if (bmp != null) {
                setImageViewBitmap(R.id.ride_map, bmp)
                setViewVisibility(R.id.ride_map, View.VISIBLE)
            } else {
                setViewVisibility(R.id.ride_map, View.GONE)
            }
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setColor(MAPP_TEAL)
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
