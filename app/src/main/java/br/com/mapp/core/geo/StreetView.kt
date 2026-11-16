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
import kotlin.math.roundToInt
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
     * Sem `heading`: o Google vira a câmera da foto mais próxima para o ponto pedido.
     * `return_error_code`: sem foto devolve erro em vez da imagem cinza "sem imagens".
     */
    fun imageUrl(point: GeoPoint, key: String, zoom: StreetZoom = StreetZoom.DEFAULT): String =
        "$HOST?size=${WIDTH}x$HEIGHT&location=${coord(point)}&radius=$RADIUS_M&source=outdoor" +
            "&fov=${zoom.fov}&pitch=0&return_error_code=true&key=${enc(key)}"

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

    /** A foto muda com o ponto e com o zoom pedido. */
    fun cacheKey(point: GeoPoint, zoom: StreetZoom): String = "z${zoom.id}:${coord(point)}"

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
 * pedido ao Google, a fachada do ponto fica maior no quadro pequeno do Card. [X1] = como era antes.
 */
enum class StreetZoom(val id: String, val label: String, val factor: Double) {
    X1("1", "1×", 1.0),
    X1_5("1.5", "1,5×", 1.5),
    X2("2", "2×", 2.0),
    X2_5("2.5", "2,5×", 2.5),
    X3("3", "3×", 3.0);

    val fov: Int get() = StreetView.fovFor(factor)

    companion object {
        val DEFAULT = X2

        fun fromId(id: String?): StreetZoom = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
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
