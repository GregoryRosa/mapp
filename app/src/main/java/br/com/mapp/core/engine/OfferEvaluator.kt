package br.com.mapp.core.engine

import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneHit
import br.com.mapp.core.model.ZoneType

/**
 * Regras do semáforo (cada critério do perfil tem marca amarela e verde — ver FilterProfile):
 * - VERMELHO se qualquer critério passar da marca amarela (R$/km, R$/h, valor ou nota abaixo do
 *   mínimo; busca acima do máximo em km ou minutos) ou coleta/destino estiver em zona perigosa com alerta.
 * - VERDE se TODOS os critérios atingirem a marca verde: R$/km, R$/h, valor e nota ≥ "bom"; busca
 *   km e minutos ≤ "bom". Valor não lido ou marca verde desligada (null) não impedem o verde.
 * - AMARELO nos demais casos.
 * Km/min totais = deslocamento até o passageiro + viagem. Trecho com km mas sem tempo (ou o
 * contrário) — o leitor não conseguiu ler um dos números — tem a parte que falta estimada pela
 * velocidade ([legs]); antes contava como zero e o R$/h saía absurdo (R$ 151/h numa corrida de 4,7 km).
 */
object OfferEvaluator {

    /** Velocidade usada quando nenhum trecho tem tempo e km legíveis (média urbana). */
    const val DEFAULT_SPEED_KMH = 25.0
    private const val MIN_SPEED_KMH = 15.0
    private const val MAX_SPEED_KMH = 40.0

    /** Soma dos trechos; [kmEstimated]/[timeEstimated] = alguma parte veio da velocidade, não da tela. */
    data class LegTotals(
        val km: Double?,
        val minutes: Double?,
        val kmEstimated: Boolean,
        val timeEstimated: Boolean,
    )

    /**
     * Km e minutos totais. Trecho sem nenhum dos dois não entra (ex.: oferta só com a viagem).
     * Trecho com um só dos dois: o outro sai da velocidade do trecho completo (limitada a 15–40 km/h),
     * ou de [DEFAULT_SPEED_KMH].
     */
    fun legs(raw: RawOffer): LegTotals {
        val speed = speedKmh(raw)
        var kmEstimated = false
        var timeEstimated = false
        fun km(km: Double?, min: Int?): Double? = km ?: min?.let { kmEstimated = true; it * speed / 60.0 }
        fun min(km: Double?, min: Int?): Double? = min?.toDouble() ?: km?.let { timeEstimated = true; it / speed * 60.0 }
        val totalKm = sumOrNull(km(raw.pickupKm, raw.pickupMin), km(raw.tripKm, raw.tripMin))
        val totalMin = sumOrNull(min(raw.pickupKm, raw.pickupMin), min(raw.tripKm, raw.tripMin))
        return LegTotals(totalKm, totalMin, kmEstimated, timeEstimated)
    }

    private fun speedKmh(raw: RawOffer): Double {
        fun of(km: Double?, min: Int?): Double? =
            if (km != null && min != null && km > 0 && min > 0) km / min * 60.0 else null
        val measured = of(raw.tripKm, raw.tripMin) ?: of(raw.pickupKm, raw.pickupMin) ?: return DEFAULT_SPEED_KMH
        return measured.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
    }

    fun metrics(raw: RawOffer, cost: VehicleCostConfig): OfferMetrics {
        val legs = legs(raw)
        val totalKm = legs.km
        val totalMin = legs.minutes?.let { Math.round(it).toInt() }
        val perKm = totalKm?.takeIf { it > 0 }?.let { raw.price / it }
        val perHour = legs.minutes?.takeIf { it > 0 }?.let { raw.price / it * 60.0 }
        val net = totalKm?.let { raw.price - it * cost.costPerKm }
        val netPerKm = if (net != null && totalKm > 0) net / totalKm else null
        return OfferMetrics(totalKm, totalMin, perKm, perHour, net, netPerKm)
    }

    /**
     * Ofertas gravadas antes da estimativa (trecho incompleto contado como zero): refaz km/tempo e o
     * que depende deles. O custo/km da época sai dos próprios números gravados (valor − lucro) ÷ km.
     */
    fun repaired(raw: RawOffer, stored: OfferMetrics): OfferMetrics {
        val legs = legs(raw)
        if (!legs.kmEstimated && !legs.timeEstimated) return stored
        val totalKm = legs.km
        val perKm = totalKm?.takeIf { it > 0 }?.let { raw.price / it }
        val perHour = legs.minutes?.takeIf { it > 0 }?.let { raw.price / it * 60.0 }
        val costPerKm = stored.netProfit?.let { net -> stored.totalKm?.takeIf { it > 0 }?.let { (raw.price - net) / it } }
        val net = if (costPerKm != null && totalKm != null) raw.price - totalKm * costPerKm else stored.netProfit
        val netPerKm = if (net != null && totalKm != null && totalKm > 0) net / totalKm else null
        return OfferMetrics(totalKm, legs.minutes?.let { Math.round(it).toInt() }, perKm, perHour, net, netPerKm)
    }

    fun zoneHits(zones: List<Zone>, pickup: GeoPoint?, dropoff: GeoPoint?): List<ZoneHit> = buildList {
        zones.forEach { z ->
            if (pickup != null && Geo.contains(z.polygon, pickup)) add(ZoneHit(z, PointRole.PICKUP))
            if (dropoff != null && Geo.contains(z.polygon, dropoff)) add(ZoneHit(z, PointRole.DROPOFF))
        }
    }

    fun evaluate(
        raw: RawOffer,
        profile: FilterProfile,
        cost: VehicleCostConfig,
        zones: List<Zone> = emptyList(),
        pickup: GeoPoint? = null,
        dropoff: GeoPoint? = null,
    ): EvaluatedOffer {
        val m = metrics(raw, cost)
        val hits = zoneHits(zones, pickup, dropoff)
        val reasons = mutableListOf<String>()

        profile.minPrice?.let { if (raw.price < it) reasons += "Valor abaixo de R$ %.2f".format(it) }
        profile.minPerKm?.let { min -> m.perKm?.let { if (it < min) reasons += "R$/km abaixo de %.2f".format(min) } }
        profile.minPerHour?.let { min -> m.perHour?.let { if (it < min) reasons += "R$/h abaixo de %.0f".format(min) } }
        profile.maxPickupKm?.let { max -> raw.pickupKm?.let { if (it > max) reasons += "Busca acima de %.1f km".format(max) } }
        profile.maxPickupMin?.let { max -> raw.pickupMin?.let { if (it > max) reasons += "Busca acima de $max min" } }
        profile.minRating?.let { min -> raw.passengerRating?.let { if (it < min) reasons += "Nota do passageiro abaixo de $min" } }
        hits.filter { it.zone.type == ZoneType.DANGER && it.zone.alertEnabled }.forEach {
            val onde = if (it.role == PointRole.PICKUP) "Coleta" else "Destino"
            reasons += "$onde em zona perigosa: ${it.zone.name}"
        }

        val signal = when {
            reasons.isNotEmpty() -> Signal.RED
            isGood(raw, m, profile) -> Signal.GREEN
            else -> Signal.YELLOW
        }
        hits.filter { it.zone.type == ZoneType.HIGH_DEMAND }.forEach {
            val onde = if (it.role == PointRole.PICKUP) "Coleta" else "Destino"
            reasons += "$onde em alta demanda: ${it.zone.name}"
        }
        return EvaluatedOffer(raw, m, signal, reasons, pickup, dropoff, hits)
    }

    /** Todos os critérios na marca verde (valor não lido ou marca null não impedem). */
    private fun isGood(raw: RawOffer, m: OfferMetrics, p: FilterProfile): Boolean =
        meets(m.perKm, p.goodPerKm) &&
            meets(m.perHour, p.goodPerHour) &&
            meets(raw.price, p.goodPrice) &&
            meets(raw.passengerRating, p.goodRating) &&
            atMost(raw.pickupKm, p.goodPickupKm) &&
            atMost(raw.pickupMin?.toDouble(), p.goodPickupMin?.toDouble())

    /** Mais é melhor: verde a partir de [target]. */
    private fun meets(value: Double?, target: Double?): Boolean =
        target == null || value == null || value >= target

    /** Menos é melhor: verde até [target]. */
    private fun atMost(value: Double?, target: Double?): Boolean =
        target == null || value == null || value <= target

    private fun sumOrNull(a: Double?, b: Double?): Double? =
        if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
}
