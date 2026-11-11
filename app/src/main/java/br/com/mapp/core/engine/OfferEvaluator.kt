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
 * Regras do semáforo:
 * - VERMELHO se qualquer critério mínimo falhar ou coleta/destino estiver em zona perigosa com alerta.
 * - VERDE se R$/km e R$/hora atingirem os valores "bons" (critério sem dado é ignorado).
 * - AMARELO nos demais casos.
 * Km/min totais = deslocamento até o passageiro + viagem.
 */
object OfferEvaluator {

    fun metrics(raw: RawOffer, cost: VehicleCostConfig): OfferMetrics {
        val totalKm = sumOrNull(raw.tripKm, raw.pickupKm)
        val totalMin = sumOrNull(raw.tripMin?.toDouble(), raw.pickupMin?.toDouble())?.toInt()
        val perKm = totalKm?.takeIf { it > 0 }?.let { raw.price / it }
        val perHour = totalMin?.takeIf { it > 0 }?.let { raw.price / it * 60.0 }
        val net = totalKm?.let { raw.price - it * cost.costPerKm }
        val netPerKm = if (net != null && totalKm > 0) net / totalKm else null
        return OfferMetrics(totalKm, totalMin, perKm, perHour, net, netPerKm)
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
            meets(m.perKm, profile.goodPerKm) && meets(m.perHour, profile.goodPerHour) -> Signal.GREEN
            else -> Signal.YELLOW
        }
        hits.filter { it.zone.type == ZoneType.HIGH_DEMAND }.forEach {
            val onde = if (it.role == PointRole.PICKUP) "Coleta" else "Destino"
            reasons += "$onde em alta demanda: ${it.zone.name}"
        }
        return EvaluatedOffer(raw, m, signal, reasons, pickup, dropoff, hits)
    }

    private fun meets(value: Double?, target: Double?): Boolean =
        target == null || value == null || value >= target

    private fun sumOrNull(a: Double?, b: Double?): Double? =
        if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
}
