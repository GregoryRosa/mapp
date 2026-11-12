package br.com.mapp.core.pin

import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.ZoneType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marcação rápida feita na rua (botão 📍 no painel do ícone "M"): só um ponto e o tipo.
 * Depois, com calma, vira uma [br.com.mapp.core.model.Zone] no editor de zonas.
 * [id] = momento da marcação (ms), único por aparelho.
 */
data class ZonePin(
    val id: Long,
    val type: ZoneType,
    val point: GeoPoint,
)

object ZonePins {
    /** Tipos na ordem dos botões do painel. */
    val QUICK_TYPES = listOf(ZoneType.DANGER, ZoneType.HIGH_DEMAND, ZoneType.CUSTOM)

    fun add(pins: List<ZonePin>, pin: ZonePin): List<ZonePin> =
        (pins.filterNot { it.id == pin.id } + pin).sortedBy { it.id }

    fun remove(pins: List<ZonePin>, id: Long): List<ZonePin> = pins.filterNot { it.id == id }

    /**
     * Polígono regular em volta do pin (zona rápida, sem desenhar). [radiusM] em metros;
     * aproximação plana, boa para raios de algumas centenas de metros.
     */
    fun circle(center: GeoPoint, radiusM: Double = DEFAULT_RADIUS_M, sides: Int = 12): List<GeoPoint> {
        require(sides >= 3) { "sides >= 3" }
        val dLat = radiusM / METERS_PER_DEG_LAT
        val dLon = radiusM / (METERS_PER_DEG_LAT * cos(center.lat * PI / 180.0))
        return List(sides) { i ->
            val a = 2 * PI * i / sides
            GeoPoint(center.lat + dLat * sin(a), center.lon + dLon * cos(a))
        }
    }

    const val DEFAULT_RADIUS_M = 150.0
    private const val METERS_PER_DEG_LAT = 111_320.0
}

/** Texto salvo no aparelho: uma linha por pin, "v1<TAB>id<TAB>tipo<TAB>lat<TAB>lon". */
object ZonePinCodec {
    private const val SEP = "\t"

    fun encode(pins: List<ZonePin>): String =
        pins.joinToString("\n") { listOf("v1", it.id, it.type.name, it.point.lat, it.point.lon).joinToString(SEP) }

    /** Linhas inválidas são ignoradas (não derrubam as outras). */
    fun decode(text: String?): List<ZonePin> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { row ->
            val c = row.split(SEP)
            if (c.size < 5 || c[0] != "v1") return@mapNotNull null
            val id = c[1].toLongOrNull() ?: return@mapNotNull null
            val type = ZoneType.entries.firstOrNull { it.name == c[2] } ?: ZoneType.CUSTOM
            val lat = c[3].toDoubleOrNull() ?: return@mapNotNull null
            val lon = c[4].toDoubleOrNull() ?: return@mapNotNull null
            ZonePin(id, type, GeoPoint(lat, lon))
        }
    }
}
