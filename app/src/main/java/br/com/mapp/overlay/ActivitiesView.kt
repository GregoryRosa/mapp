package br.com.mapp.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import br.com.mapp.core.notify.MapFrame
import br.com.mapp.core.notify.RideNotices
import br.com.mapp.core.notify.RidePoints
import br.com.mapp.core.route.RideRoute
import br.com.mapp.core.route.RouteStop
import br.com.mapp.maps.MapSnapshots
import br.com.mapp.maps.MapViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Corrida aceita em andamento, com os dados do aceite (os mesmos da notificação). */
data class RideActivity(
    val key: Any,
    val offer: EvaluatedOffer,
    val category: String?,
    val passenger: String?,
    val onBoard: Boolean,
    val acceptedAt: Long,
)

/** O que o menu Atividades mostra: corrida atual, próxima (na fila) e o que o mapa precisa. */
data class RideActivities(
    val current: RideActivity?,
    val next: RideActivity?,
    val here: GeoPoint?,
    val zones: List<Zone>,
)

/**
 * Conteúdo do menu **Atividades** do ícone "M": a corrida atual e a próxima (aceita durante a atual),
 * cada uma do jeito da notificação da corrida aceita aberta — título com o semáforo, busca → viagem,
 * mapa + fotos de A e do destino no modelo de Card → Notificação → Mapa e fotos e as linhas escolhidas lá.
 * Mapa e fotos chegam depois (mesmo cache do Card e da notificação) e ficam guardados por corrida.
 * Só mostra — não mexe em nada da corrida.
 */
class ActivitiesView(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Mapa/foto chegou e o conteúdo mudou de tamanho: a janela mede de novo. */
    private val onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    /** Mapa já desenhado por corrida (assinatura = pontos + largura). */
    private val maps = LinkedHashMap<Any, Pair<String, Bitmap?>>()
    private val mapJobs = HashMap<Any, Job>()

    /** Fotos por ponto (letra + coordenada), da memória do [StreetViewPhotos]. */
    private val photos = HashMap<String, StreetViewPhotos.Result>()
    private val photoJobs = HashMap<String, Job>()

    private var widthPx = 0
    private var last: RideActivities? = null

    private val currentSlot = Slot("CORRIDA ATUAL", "Nenhuma corrida em andamento")
    private val nextSlot = Slot("PRÓXIMA CORRIDA", "Nenhuma corrida aceita na fila")

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(text(12f, SECONDARY).apply { text = "ATIVIDADES" })
        addView(currentSlot.view, fullWidth(8))
        addView(nextSlot.view, fullWidth(8))
        addView(button("Fechar") { onClose() }, fullWidth(10))
    }

    /** Mostra [data] com o painel de [widthPx] de largura (o mapa é desenhado nesse tamanho). */
    fun bind(data: RideActivities, widthPx: Int) {
        this.widthPx = widthPx
        last = data
        currentSlot.bind(data.current, data)
        nextSlot.bind(data.next, data)
        // Corridas que saíram do menu: libera mapa e trabalho pendente.
        val keep = listOfNotNull(data.current?.key, data.next?.key).toSet()
        (maps.keys - keep).forEach { k -> maps.remove(k) }
        (mapJobs.keys - keep).forEach { k -> mapJobs.remove(k)?.cancel() }
    }

    /** Menu fechado: para o que está carregando (mapas prontos ficam para a próxima vez). */
    fun closed() {
        mapJobs.values.forEach { it.cancel() }
        mapJobs.clear()
        photoJobs.values.forEach { it.cancel() }
        photoJobs.clear()
        photos.clear() // as fotos continuam no cache do StreetViewPhotos
        last = null
    }

    fun destroy() {
        closed()
        maps.clear()
    }

    private fun rebind() {
        val d = last ?: return
        bind(d, widthPx)
        onChanged()
    }

    // ------------------------------------------------------------------ uma corrida

    private inner class Slot(private val caption: String, emptyText: String) {
        private val header = text(11f, SECONDARY)
        private val empty = text(13f, SECONDARY).apply { text = emptyText }
        private val title = text(15f, WHITE, bold = true)
        private val summary = text(13f, SECONDARY)
        private val map = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Mapa da corrida: você, coleta (A) e destino"
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(PHOTO_BG) }
            clipToOutline = true
        }
        private val tiles = linkedMapOf(MediaBlock.PICKUP to PhotoTile(), MediaBlock.DROPOFF to PhotoTile())

        /** Quadros soltos em posições absolutas, no modelo de Card → Notificação → Mapa e fotos. */
        private val media = FrameLayout(context).apply {
            addView(map)
            tiles.values.forEach { addView(it.view) }
        }
        private val note = text(11f, SECONDARY)
        private val lines = List(RideNotices.MAX_DETAILS) { text(13f, WHITE) }
        private val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            addView(title)
            addView(summary, fullWidth(2))
            addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MediaSizes.NOTICE.single)).apply { topMargin = dp(6) })
            addView(note, fullWidth(2))
            lines.forEach { addView(it, fullWidth(3)) }
        }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(empty, fullWidth(4))
            addView(box, fullWidth(4))
        }

        fun bind(ride: RideActivity?, data: RideActivities) {
            if (ride == null) {
                header.text = caption
                empty.visibility = View.VISIBLE
                box.visibility = View.GONE
                return
            }
            empty.visibility = View.GONE
            box.visibility = View.VISIBLE
            header.text = "$caption · aceita às ${CLOCK.format(Instant.ofEpochMilli(ride.acceptedAt).atZone(ZoneId.systemDefault()))}"

            val o = ride.offer
            val layout = OverlayPrefs.noticeLayout(context)
            val points = RideNotices.points(data.here, o.pickup, o.dropoff, o.stops)
            val text = RideNotices.of(o, ride.category, ride.passenger, ride.acceptedAt, ZoneId.systemDefault(), ride.onBoard, points, layout)
            title.text = text.title
            summary.text = text.summary
            summary.visibility = if (text.summary.isEmpty()) View.GONE else View.VISIBLE
            box.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(SECTION_BG)
                // Borda na cor da notificação: filtro do Card que pinta, ou roxo na corrida com parada.
                val filterColor = CardFilters.paintColor(CardFilters.matching(OverlayPrefs.cardFilters(context), o))
                val border = filterColor ?: if (text.hasStops) CardHeaderView.STOP_PURPLE else null
                if (border != null) setStroke(dp(2), border)
            }
            lines.forEachIndexed { i, tv ->
                val line = text.details.getOrNull(i)
                tv.text = line.orEmpty()
                tv.visibility = if (line != null) View.VISIBLE else View.GONE
            }

            // Fotos da coleta e do destino (rua ou satélite), como em Card → Notificação, com a chave colada.
            val photosOn = layout.showPhotos && OverlayPrefs.streetViewEnabled(context)
            val stops = if (photosOn) MediaPhotos.stops(RideRoute.of(o)) else emptyMap()
            // Mapa (desligado em Card → Notificação: nem desenha).
            val frame = if (text.showMap) RideNotices.frame(points.all) else null
            val available = stops.keys + listOfNotNull(MediaBlock.MAP.takeIf { frame != null })
            val placed = placeMedia(layout.media, available)
            tiles.forEach { (block, tile) ->
                val cell = placed[block]
                place(tile.view, cell)
                if (cell != null) tile.bind(stops[block], layout.photoView, SatelliteShot.forAspect(cell.width.toDouble() / cell.height))
            }
            val mapCell = placed[MediaBlock.MAP]
            place(map, mapCell)
            val mapState = if (frame != null && mapCell != null) mapFor(ride, points, data, frame, mapCell) else null
            val bmp = mapState?.second
            if (bmp != null) map.setImageBitmap(bmp) else map.setImageDrawable(null)
            val waiting = mapCell != null && mapState == null
            val failed = mapCell != null && mapState != null && bmp == null
            val mapNote = when {
                waiting -> "Carregando mapa…"
                failed -> "Mapa indisponível agora (sem internet?)"
                else -> null
            } ?: text.mapNote
            note.text = mapNote.orEmpty()
            note.visibility = if (mapNote != null) View.VISIBLE else View.GONE
        }

        /** Lugar (px) de cada quadro no painel; a altura da área acompanha o modelo. Vazio = sem área. */
        private fun placeMedia(layout: MediaLayout, available: Set<MediaBlock>): Map<MediaBlock, MediaPlaced> {
            val area = layout.arrange(available)
            if (area == null) {
                media.visibility = View.GONE
                return emptyMap()
            }
            val sizes = MediaSizes.NOTICE
            val heightDp = MediaLayout.height(area, sizes)
            media.visibility = View.VISIBLE
            val params = media.layoutParams
            if (params.height != dp(heightDp)) {
                params.height = dp(heightDp)
                media.layoutParams = params
            }
            return MediaLayout.place(area, innerWidth(), dp(heightDp), sizes, gap = dp(sizes.gap)).associateBy { it.block }
        }
    }

    /** Largura útil do quadro da corrida: painel menos as margens do painel e do quadro. */
    private fun innerWidth(): Int = (widthPx - dp(2 * 12 + 2 * 10)).coerceAtLeast(dp(120))

    private fun place(view: View, cell: MediaPlaced?) {
        if (cell == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val lp = view.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == cell.width && lp.height == cell.height && lp.leftMargin == cell.x && lp.topMargin == cell.y) return
        view.layoutParams = FrameLayout.LayoutParams(cell.width, cell.height).apply {
            leftMargin = cell.x
            topMargin = cell.y
        }
    }

    /**
     * Mapa da corrida: null = ainda desenhando; (assinatura, null) = não deu. Desenha uma vez por
     * corrida, pontos e tamanho do quadro (a posição de agora entra só no 1º desenho, para não refazer
     * a cada GPS).
     */
    private fun mapFor(
        ride: RideActivity,
        points: RidePoints,
        data: RideActivities,
        frame: MapFrame,
        cell: MediaPlaced,
    ): Pair<String, Bitmap?>? {
        val width = cell.width
        val height = cell.height
        val signature = "${points.pickup}|${points.dropoff}|${points.stops}|${width}x$height"
        maps[ride.key]?.let { if (it.first == signature) return it }
        if (mapJobs[ride.key]?.isActive == true && maps[ride.key] == null) return null
        maps.remove(ride.key)
        mapJobs.remove(ride.key)?.cancel()
        val route = RideRoute.of(ride.offer.raw, points.pickup, points.stops, points.dropoff)
        val key = ride.key
        mapJobs[key] = scope.launch {
            val drawn = MapSnapshots.render(context, frame, width, height, points.here, route, data.zones, TILE_TIMEOUT_MS)
            maps[key] = signature to drawn
            mapJobs.remove(key)
            rebind()
        }
        return null
    }

    // ------------------------------------------------------------------ foto da rua / satélite

    private inner class PhotoTile {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WHITE)
            gravity = Gravity.CENTER
        }
        private val message = text(11f, SECONDARY).apply { gravity = Gravity.CENTER }
        private val credit = text(9f, 0xDDFFFFFF.toInt()).apply { setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt()) }
        val view = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(PHOTO_BG) }
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(22)
            })
            addView(badge, FrameLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(3); topMargin = dp(3) })
            addView(credit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                marginEnd = dp(4)
                bottomMargin = dp(2)
            })
        }

        fun bind(stop: RouteStop?, mode: PhotoView, shot: SatelliteShot) {
            if (stop == null) return
            badge.text = stop.letter
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MapViews.colorOf(stop.kind))
                setStroke(dp(1), WHITE)
            }
            val point = stop.point
            val result = point?.let { photoFor(stop, mode, shot) }
            when {
                result is StreetViewPhotos.Result.Photo -> show(result.bitmap, result.credit, satellite = result.satellite)
                result is StreetViewPhotos.Result.Missing -> show(null, null, result.message)
                point != null -> show(null, null, if (mode == PhotoView.SATELLITE) "Carregando satélite…" else "Carregando foto…")
                stop.address != null -> show(null, null, "Localizando endereço…")
                else -> show(null, null, "Endereço não lido")
            }
        }

        /** Satélite inteiro (sem cortar o logo/créditos do Google); foto da rua preenche o quadro. */
        private fun show(bmp: Bitmap?, creditText: String?, msg: String? = null, satellite: Boolean = false) {
            image.scaleType = if (satellite) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (bmp != null) image.setImageBitmap(bmp) else image.setImageDrawable(null)
            credit.text = creditText.orEmpty()
            credit.visibility = if (creditText.isNullOrEmpty()) View.GONE else View.VISIBLE
            message.text = msg.orEmpty()
            message.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
    }

    /** Foto já carregada, ou começa a carregar (null = ainda não chegou). */
    private fun photoFor(stop: RouteStop, mode: PhotoView, shot: SatelliteShot): StreetViewPhotos.Result? {
        val point = stop.point ?: return null
        val id = "${mode.id}|${shot.width}x${shot.height}|${stop.letter}@${point.lat},${point.lon}"
        photos[id]?.let { return it }
        if (photoJobs[id]?.isActive == true) return null
        photoJobs[id] = scope.launch {
            photos[id] = StreetViewPhotos.load(context, stop, mode, shot)
            photoJobs.remove(id)
            rebind()
        }
        return null
    }

    // ------------------------------------------------------------------ views

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(BUTTON_BG) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun fullWidth(topMarginDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topMarginDp) }

    private companion object {
        const val TILE_TIMEOUT_MS = 10_000L
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val WHITE = 0xFFFFFFFF.toInt()
        val SECONDARY = 0xFFBDBDBD.toInt()
        val SECTION_BG = 0xFF2D2F33.toInt()
        val BUTTON_BG = 0xFF3C4043.toInt()
        val PHOTO_BG = 0xFF2C2F33.toInt()
    }
}
