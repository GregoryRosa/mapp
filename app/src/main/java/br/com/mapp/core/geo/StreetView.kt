package br.com.mapp.core.geo

import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.route.RouteStop
import br.com.mapp.core.route.StopKind
import java.net.URLEncoder
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fotos da rua (Google Street View Static API) dos pontos da corrida, embaixo do mini mapa do Card.
 * Primeiro a consulta de metadados (grátis): diz se há foto perto do ponto, a data e o crédito.
 * Só então a foto (paga depois de [FREE_PER_MONTH] no mês; o MApp para em [LIMIT_PER_MONTH]).
 */
object StreetView {
    const val HOST = "https://maps.googleapis.com/maps/api/streetview"

    /** Procura a foto mais próxima até esta distância do ponto, em metros. */
    const val RADIUS_M = 80

    /** Tamanho pedido (640 é o máximo da API): serve para a miniatura e para a foto ampliada. */
    const val WIDTH = 640
    const val HEIGHT = 420

    /** Fotos grátis por mês no Google; o MApp para antes, com folga. */
    const val FREE_PER_MONTH = 10_000
    const val LIMIT_PER_MONTH = 9_500

    /** Fotos no Card: coleta, paradas e destino, no máximo estas. */
    const val MAX_PHOTOS = 3

    fun metadataUrl(point: GeoPoint, key: String): String =
        "$HOST/metadata?location=${coord(point)}&radius=$RADIUS_M&source=outdoor&key=${enc(key)}"

    /**
     * Foto do ponto. [framing] decide para onde a câmera olha:
     * - [StreetFraming.FACADE]: sem `heading` e sem inclinação — o Google vira a câmera da foto mais
     *   próxima para o ponto pedido (a fachada da casa), como o MApp fazia antes.
     * - [StreetFraming.STREET]: `heading` girado 90° ([streetHeading], precisa do ponto de onde a
     *   foto foi tirada, [pano], que vem dos metadados) — a câmera olha ao longo da via.
     * - [StreetFraming.MIXED]: rumo da fachada com a câmera inclinada para baixo (o chão na frente
     *   do ponto e um pedaço da fachada).
     * `pitch` negativo = câmera para baixo ([tiltDeg] graus). `return_error_code`: sem foto devolve
     * erro em vez da imagem cinza "sem imagens".
     */
    fun imageUrl(
        point: GeoPoint,
        key: String,
        zoom: StreetZoom = StreetZoom.DEFAULT,
        framing: StreetFraming = StreetFraming.FACADE,
        tiltDeg: Int = 0,
        pano: GeoPoint? = null,
    ): String {
        val heading = if (framing == StreetFraming.STREET && pano != null) streetHeading(pano, point) else null
        val aim = heading?.let { "&heading=" + String.format(Locale.US, "%.1f", it) }.orEmpty()
        return "$HOST?size=${WIDTH}x$HEIGHT&location=${coord(point)}&radius=$RADIUS_M&source=outdoor$aim" +
            "&fov=${zoom.fov}&pitch=${-framing.tilt(tiltDeg)}&return_error_code=true&key=${enc(key)}"
    }

    /** Abaixo disso a foto está praticamente sobre o ponto: não há rumo confiável para a via. */
    const val MIN_HEADING_M = 4.0

    /** Raio da Terra, em metros. */
    private const val EARTH_M = 6_371_000.0

    /** Rumo de [from] para [to], em graus (0 = norte, 90 = leste). */
    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Distância aproximada em metros (basta para as dezenas de metros entre a foto e o ponto). */
    fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians((a.lat + b.lat) / 2)
        val dx = Math.toRadians(b.lon - a.lon) * cos(lat)
        val dy = Math.toRadians(b.lat - a.lat)
        return EARTH_M * hypot(dx, dy)
    }

    /**
     * Para onde virar a câmera para olhar ao longo da rua: a foto do Google é tirada de dentro da
     * via, então o rumo dela para o endereço aponta para a calçada — girando 90° a câmera passa a
     * olhar a pista (asfalto, paralelepípedo, terra). null se a foto está quase sobre o ponto.
     */
    fun streetHeading(pano: GeoPoint, point: GeoPoint): Double? {
        if (metersBetween(pano, point) < MIN_HEADING_M) return null
        return (bearing(pano, point) + 90.0) % 360.0
    }

    /** Campo de visão da foto sem zoom (1×), em graus — o que o MApp pedia antes do zoom. */
    const val BASE_FOV = 80

    /**
     * Campo de visão (`fov`, 10–120° na API) para o zoom [factor]: a mesma conta de uma câmera
     * (metade da largura vista ÷ zoom). 2× ≈ 46°: a fachada aparece com o dobro do tamanho.
     */
    fun fovFor(factor: Double): Int {
        val half = Math.toRadians(BASE_FOV / 2.0)
        val fov = Math.toDegrees(2 * atan(tan(half) / factor.coerceAtLeast(0.1)))
        return fov.roundToInt().coerceIn(10, 120)
    }

    /** Pontos a menos de ~1 m (5 casas decimais) usam a mesma foto. */
    fun cacheKey(point: GeoPoint): String = coord(point)

    /** A foto muda com o ponto, o zoom, o enquadramento e a inclinação pedidos. */
    fun cacheKey(
        point: GeoPoint,
        zoom: StreetZoom,
        framing: StreetFraming = StreetFraming.FACADE,
        tiltDeg: Int = 0,
    ): String = "z${zoom.id}${framing.tag(tiltDeg)}:${coord(point)}"

    /**
     * Quais pontos ganham foto, na ordem do trajeto: coleta e destino sempre; paradas só se têm
     * endereço ou ponto (a Uber mostra "1 parada" sem endereço — não há o que fotografar).
     * Mais de [MAX_PHOTOS]: coleta, as primeiras paradas e o destino.
     */
    fun pick(route: List<RouteStop>): List<RouteStop> {
        val useful = route.filter { it.kind != StopKind.STOP || it.address != null || it.point != null }
        if (useful.size <= MAX_PHOTOS) return useful
        val stops = useful.filter { it.kind == StopKind.STOP }.take(MAX_PHOTOS - 2)
        return listOf(useful.first()) + stops + useful.last()
    }

    /** "2023-05" (data dos metadados) → "05/2023"; formato inesperado → null. */
    fun dateLabel(date: String?): String? {
        val m = Regex("""^(\d{4})-(\d{2})""").find(date?.trim().orEmpty()) ?: return null
        return "${m.groupValues[2]}/${m.groupValues[1]}"
    }

    /** Crédito da foto ("© Google" ou o autor) + data, para o rodapé da foto. */
    fun credit(copyright: String?, date: String?): String {
        val who = copyright?.trim()?.takeIf { it.isNotEmpty() } ?: "© Google"
        return listOfNotNull(who, dateLabel(date)).joinToString(" · ")
    }

    private fun coord(p: GeoPoint) = String.format(Locale.US, "%.5f,%.5f", p.lat, p.lon)

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}

/**
 * Zoom das fotos da rua (Card → Card de corridas → Fotos da rua e satélite): menos campo de visão
 * pedido ao Google, a fachada do ponto fica maior no quadro pequeno do Card. [X1] = como era antes (padrão).
 */
enum class StreetZoom(val id: String, val label: String, val factor: Double) {
    X1("1", "1×", 1.0),
    X1_5("1.5", "1,5×", 1.5),
    X2("2", "2×", 2.0),
    X2_5("2.5", "2,5×", 2.5),
    X3("3", "3×", 3.0);

    val fov: Int get() = StreetView.fovFor(factor)

    companion object {
        /** Padrão = como era antes (a foto da rua já estava boa; o zoom fica para quem quiser). */
        val DEFAULT = X1

        fun fromId(id: String?): StreetZoom = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Enquadramento da foto da rua (Card → Card de corridas → Fotos da rua e satélite): para onde a
 * câmera olha no ponto. [STREET] é o padrão — o pedido era ver **a rua** (asfalto, paralelepípedo,
 * terra, buraco, ladeira), não o telhado da casa.
 */
enum class StreetFraming(val id: String, val label: String, val hint: String) {
    FACADE("fachada", "Fachada", "Câmera virada para o endereço, sem inclinação (como era antes)."),
    STREET("rua", "Rua", "Câmera ao longo da via, inclinada para baixo: mostra se é asfalto, paralelepípedo ou terra."),
    MIXED("mista", "Mista", "Rumo da fachada, inclinada para baixo: o chão na frente do ponto e um pedaço da fachada.");

    /** Inclinação para baixo usada, em graus: a fachada é sempre reta. */
    fun tilt(deg: Int): Int = if (this == FACADE) 0 else StreetTilt.snap(deg)

    /** Pedaço do nome da foto no cache; vazio na fachada, para a foto já buscada continuar valendo. */
    fun tag(deg: Int): String = if (this == FACADE) "" else "-$id${tilt(deg)}"

    companion object {
        /** Padrão = Rua. */
        val DEFAULT = STREET

        fun fromId(id: String?): StreetFraming = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Inclinação da câmera para baixo, em graus, nos enquadramentos Rua e Mista (slider nos ajustes).
 * 0 = câmera reta (horizonte no meio da foto), [MAX] = bem para baixo (quase só o chão).
 */
object StreetTilt {
    const val MIN = 0
    const val MAX = 45
    const val STEP = 5
    const val DEFAULT = 20

    /** Passos intermediários do slider (de [MIN] a [MAX], de [STEP] em [STEP]). */
    const val SLIDER_STEPS = (MAX - MIN) / STEP - 1

    /** Arredonda para o passo do slider e mantém dentro da faixa. */
    fun snap(deg: Int): Int = ((deg.coerceIn(MIN, MAX).toDouble() / STEP).roundToInt() * STEP)

    fun label(deg: Int): String = if (snap(deg) == 0) "reta" else "${snap(deg)}° para baixo"
}

/**
 * Imagens pedidas ao Google no mês [month] ("2026-11"): o contador zera quando o mês vira. Um
 * contador para as fotos da rua e outro para o satélite ([SatelliteView.LIMIT_PER_MONTH]).
 */
data class StreetViewBudget(val month: String = "", val used: Int = 0) {

    fun usedIn(month: String): Int = if (month == this.month) used else 0

    fun canSpend(month: String, limit: Int = StreetView.LIMIT_PER_MONTH): Boolean = usedIn(month) < limit

    fun spend(month: String): StreetViewBudget = StreetViewBudget(month, usedIn(month) + 1)

    fun encode(): String = "$month:$used"

    companion object {
        fun decode(s: String?): StreetViewBudget {
            val parts = s?.split(':') ?: return StreetViewBudget()
            val used = parts.getOrNull(1)?.toIntOrNull() ?: return StreetViewBudget()
            return StreetViewBudget(parts[0], used.coerceAtLeast(0))
        }

        fun monthOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(zone)).toString()
    }
}
