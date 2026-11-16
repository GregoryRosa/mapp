package br.com.mapp.core.geo

import br.com.mapp.core.model.GeoPoint
import java.net.URLEncoder
import java.util.Locale

/** Como aparecem as fotos dos pontos da corrida: foto da rua (Street View) ou vista de cima (satélite). */
enum class PhotoView(val id: String, val label: String) {
    STREET("street", "Foto da rua"),
    SATELLITE("satellite", "Satélite");

    /** O outro modo: o toque numa foto do Card alterna entre os dois. */
    fun other(): PhotoView = if (this == STREET) SATELLITE else STREET

    companion object {
        /** Texto salvo estranho ou ausente = foto da rua (como era antes do satélite). */
        fun fromId(id: String?): PhotoView = entries.firstOrNull { it.id == id } ?: STREET
    }
}

/**
 * Tamanho e zoom de uma imagem de satélite. [CARD] = quadro das fotos do Card (mesmo formato da foto
 * da rua); [STRIP] = faixa larga e baixa dos quadros da notificação e do painel Atividades (zoom um
 * pouco mais aberto para caber o entorno). A API aceita até 640 de cada lado.
 */
data class SatelliteShot(val width: Int, val height: Int, val zoom: Int) {
    companion object {
        /** Zoom 17: ~700 m de largura — a quadra e o entorno do ponto. */
        val CARD = SatelliteShot(StreetView.WIDTH, StreetView.HEIGHT, 17)

        /** Zoom 16: ~750 × 230 m numa faixa do tamanho dos quadros A/B da notificação. */
        val STRIP = SatelliteShot(480, 150, 16)
    }
}

/**
 * Vista de cima de um ponto da corrida (satélite com nomes das ruas) pela Google Maps Static API,
 * com a mesma chave do Street View (a API precisa estar ativada no mesmo projeto do Google Cloud).
 * Uma imagem por ponto, centrada nele, com o marcador na letra e na cor do mini mapa. O logo e os
 * créditos do Google vêm dentro da imagem e não podem ser cobertos nem cortados: quem desenha mostra
 * a imagem inteira. Cada imagem é cobrada depois de [FREE_PER_MONTH] no mês; o MApp para em [LIMIT_PER_MONTH].
 */
object SatelliteView {
    const val HOST = "https://maps.googleapis.com/maps/api/staticmap"

    /** Imagens grátis por mês no Google (Maps Static API); o MApp para antes, com folga. */
    const val FREE_PER_MONTH = 10_000
    const val LIMIT_PER_MONTH = 9_500

    /** Lado máximo da imagem na API (sem plano especial). */
    const val MAX_SIDE = 640

    /**
     * `hybrid` = satélite + nomes das ruas. [color] em ARGB (a do marcador do mini mapa); o rótulo
     * do marcador é a letra do ponto (A coleta, B… paradas, última = destino).
     */
    fun imageUrl(point: GeoPoint, key: String, letter: String, color: Int, shot: SatelliteShot = SatelliteShot.CARD): String {
        val label = label(letter)?.let { "|label:$it" }.orEmpty()
        val marker = "color:${hex(color)}$label|${coord(point)}"
        val w = shot.width.coerceIn(1, MAX_SIDE)
        val h = shot.height.coerceIn(1, MAX_SIDE)
        return "$HOST?center=${coord(point)}&zoom=${shot.zoom}&size=${w}x$h&scale=1&maptype=hybrid&format=jpg" +
            "&markers=${enc(marker)}&key=${enc(key)}"
    }

    /** A imagem muda com o ponto (~1 m), o tamanho/zoom e a letra/cor do marcador. */
    fun cacheKey(point: GeoPoint, letter: String, color: Int, shot: SatelliteShot = SatelliteShot.CARD): String =
        "sat:${shot.width}x${shot.height}z${shot.zoom}:${coord(point)}:${label(letter).orEmpty()}:${hex(color)}"

    /** Cor ARGB → "0xRRGGBB" (formato da API; a transparência é ignorada). */
    fun hex(color: Int): String = String.format(Locale.US, "0x%06X", color and 0xFFFFFF)

    /** Rótulo do marcador: a API só aceita uma letra maiúscula ou um número; fora disso, sem rótulo. */
    fun label(letter: String): String? {
        val c = letter.trim().firstOrNull()?.uppercaseChar() ?: return null
        return if (c in 'A'..'Z' || c in '0'..'9') c.toString() else null
    }

    private fun coord(p: GeoPoint) = String.format(Locale.US, "%.5f,%.5f", p.lat, p.lon)

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}
