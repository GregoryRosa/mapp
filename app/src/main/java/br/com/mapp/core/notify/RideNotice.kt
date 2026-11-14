package br.com.mapp.core.notify

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.shift.ShiftMath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Textos da notificação de corrida aceita. Fechada: [title] + [summary] (busca → viagem).
 * Expandida: o mesmo + mapa + [details] (até [RideNotices.MAX_DETAILS] linhas, da mais importante)
 * e [mapNote] embaixo do mapa quando coleta/destino ficaram fora dele.
 */
data class RideNotice(
    val title: String,
    val summary: String,
    val details: List<String>,
    val mapNote: String?,
)

/** Retângulo do mapa da notificação, em graus. */
data class MapFrame(val north: Double, val south: Double, val east: Double, val west: Double) {
    val centerLat: Double get() = (north + south) / 2
    val centerLon: Double get() = (east + west) / 2
}

/** Pontos do mapa da notificação: sua posição, coleta (A) e destino (B). */
data class RidePoints(val here: GeoPoint?, val pickup: GeoPoint?, val dropoff: GeoPoint?) {
    val all: List<GeoPoint> get() = listOfNotNull(here, pickup, dropoff)
    val isEmpty: Boolean get() = all.isEmpty()
}

object RideNotices {

    /** Linhas de detalhe na notificação expandida (o layout tem esse número de linhas). */
    const val MAX_DETAILS = 5

    /** Folga em volta dos pontos, em fração do retângulo, de cada lado: marcador não fica na borda. */
    const val MARGIN = 0.2

    /** Menor lado do mapa em graus (~700 m): pontos colados não viram zoom de rua. */
    const val MIN_SPAN_DEG = 0.0065

    /** Posição mais longe que isso da coleta/destino é velha (GPS parado): fica fora do mapa. */
    const val MAX_HERE_KM = 30.0

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Monta os textos. [category] = tipo lido (UberX, Comfort...), [passenger] = 1º nome lido na tela
     * de aceite ("caio"), [acceptedAt] = quando o aceite foi visto; [onBoard] = aceite reconhecido só
     * no embarque (a chegada conta só a viagem).
     */
    fun of(
        offer: EvaluatedOffer,
        category: String?,
        passenger: String?,
        acceptedAt: Long,
        zone: ZoneId,
        onBoard: Boolean = false,
        points: RidePoints = RidePoints(null, offer.pickup, offer.dropoff),
    ): RideNotice {
        val raw = offer.raw
        val m = offer.metrics
        val legs = OfferEvaluator.legs(raw)
        val km = if (legs.kmEstimated) "~" else ""
        val time = if (legs.timeEstimated) "~" else ""

        val title = "${dot(offer.signal)} Corrida aceita · ${kind(raw.platform, category)} · ${ShiftMath.money(raw.price)}"

        val legLines = listOfNotNull(
            leg("Busca", raw.pickupKm, raw.pickupMin),
            leg("Viagem", raw.tripKm, raw.tripMin),
        )

        val total = run {
            val parts = mutableListOf<String>()
            m.totalKm?.let { parts += "$km${ShiftMath.km(it)}" }
            m.totalMin?.let { parts += "$time$it min" }
            if (parts.isEmpty()) return@run null
            val remaining = if (onBoard) raw.tripMin ?: m.totalMin else m.totalMin
            val arrival = remaining?.let {
                Instant.ofEpochMilli(acceptedAt + it * 60_000L).atZone(zone).format(CLOCK)
            }
            "Total " + parts.joinToString(" · ") + (arrival?.let { " · Chegada ~$it" } ?: "")
        }

        val metrics = listOfNotNull(
            m.perKm?.let { "$km${ShiftMath.money(it)}/km" },
            m.perHour?.let { "$time${ShiftMath.money(it)}/h" },
            m.netProfit?.let { "Lucro $km${ShiftMath.money(it)}" },
        ).joinToString(" · ").ifEmpty { null }

        val name = passenger?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
        val rating = raw.passengerRating?.let { "★ ${ShiftMath.num(it, 2)}" }
        val rider = when {
            name != null && rating != null -> "Passageiro: $name · $rating"
            name != null -> "Passageiro: $name"
            rating != null -> "Passageiro $rating"
            else -> null
        }

        val hits = offer.zoneHits.filter { it.zone.alertEnabled }.distinct()
        fun zoneLine(role: PointRole, type: ZoneType, zoneName: String): String {
            val icon = if (type == ZoneType.DANGER) "⚠" else "●"
            val where = if (role == PointRole.PICKUP) "Coleta" else "Destino"
            return "$icon $where: ${type.label} ($zoneName)"
        }
        val danger = hits.filter { it.zone.type == ZoneType.DANGER }.map { zoneLine(it.role, it.zone.type, it.zone.name) }
        val otherZones = hits.filter { it.zone.type != ZoneType.DANGER }.map { zoneLine(it.role, it.zone.type, it.zone.name) }

        // Ordem = importância: o que não cabe nas linhas da notificação sai do fim.
        val details = buildList<String> {
            metrics?.let { add(it) }
            total?.let { add(it) }
            addAll(danger)
            raw.pickupAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { add("A · $it") }
            raw.dropoffAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { add("B · $it") }
            rider?.let { add(it) }
            addAll(otherZones)
        }.distinct().take(MAX_DETAILS)

        val summary = when {
            legLines.isNotEmpty() -> legLines.joinToString(" → ")
            total != null -> total
            else -> "Distância e tempo não lidos"
        }

        return RideNotice(title, summary, details, mapNote(offer, points))
    }

    /** Aviso embaixo do mapa quando coleta/destino não aparecem nele (endereço não lido ou não achado). */
    fun mapNote(offer: EvaluatedOffer, points: RidePoints): String? {
        if (points.isEmpty) return "Sem mapa: endereços da corrida não lidos"
        val raw = offer.raw
        val missing = buildList<Pair<String, String?>> {
            if (points.pickup == null) add("Coleta" to raw.pickupAddress)
            if (points.dropoff == null) add("Destino" to raw.dropoffAddress)
        }
        if (missing.isEmpty()) return null
        val read = missing.any { !it.second.isNullOrBlank() }
        val who = if (missing.size == 2) "Coleta e destino" else missing[0].first
        val verb = if (missing.size == 2) "não aparecem" else "não aparece"
        val why = when {
            !read -> if (missing.size == 2) "endereços não lidos" else "endereço não lido"
            missing.size == 2 -> "endereços não localizados"
            else -> "endereço não localizado"
        }
        return "$who $verb no mapa: $why"
    }

    /**
     * Pontos do mapa. A posição atual fica de fora quando está a mais de [MAX_HERE_KM] da coleta (ou
     * do destino): é leitura velha do GPS e deixaria o mapa do tamanho do estado.
     */
    fun points(here: GeoPoint?, pickup: GeoPoint?, dropoff: GeoPoint?): RidePoints {
        val anchor = pickup ?: dropoff
        val keepHere = here != null && (anchor == null || Geo.haversineKm(here, anchor) <= MAX_HERE_KM)
        return RidePoints(if (keepHere) here else null, pickup, dropoff)
    }

    /** Retângulo que mostra todos os pontos com folga ([MARGIN]) e lado mínimo ([MIN_SPAN_DEG]). */
    fun frame(points: List<GeoPoint>): MapFrame? {
        if (points.isEmpty()) return null
        var north = points.maxOf { it.lat }
        var south = points.minOf { it.lat }
        var east = points.maxOf { it.lon }
        var west = points.minOf { it.lon }
        val latPad = (north - south) * MARGIN
        val lonPad = (east - west) * MARGIN
        north += latPad; south -= latPad
        east += lonPad; west -= lonPad
        if (north - south < MIN_SPAN_DEG) {
            val c = (north + south) / 2
            north = c + MIN_SPAN_DEG / 2
            south = c - MIN_SPAN_DEG / 2
        }
        if (east - west < MIN_SPAN_DEG) {
            val c = (east + west) / 2
            east = c + MIN_SPAN_DEG / 2
            west = c - MIN_SPAN_DEG / 2
        }
        return MapFrame(north, south, east, west)
    }

    private fun leg(name: String, km: Double?, min: Int?): String? {
        val parts = listOfNotNull(km?.let { ShiftMath.km(it) }, min?.let { "$it min" })
        return if (parts.isEmpty()) null else "$name ${parts.joinToString(" · ")}"
    }

    /** "UberX" na Uber (o nome já diz o app); "99 Pop", "inDrive" nas outras. */
    private fun kind(platform: Platform, category: String?): String {
        val cat = category?.trim()?.takeIf { it.isNotEmpty() } ?: return platform.label
        return if (platform == Platform.UBER || cat.startsWith(platform.label, ignoreCase = true)) cat else "${platform.label} $cat"
    }

    private fun dot(signal: Signal): String = when (signal) {
        Signal.GREEN -> "🟢"
        Signal.YELLOW -> "🟡"
        Signal.RED -> "🔴"
    }
}
