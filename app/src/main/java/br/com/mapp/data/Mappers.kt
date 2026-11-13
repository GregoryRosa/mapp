package br.com.mapp.data

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord

/** Polígono <-> texto "lat,lon;lat,lon;...". Pontos malformados são descartados na leitura. */
object PolygonCodec {
    fun encode(points: List<GeoPoint>): String =
        points.joinToString(";") { "${it.lat},${it.lon}" }

    fun decode(text: String): List<GeoPoint> =
        text.split(';').mapNotNull { part ->
            val xy = part.split(',')
            if (xy.size != 2) return@mapNotNull null
            val lat = xy[0].trim().toDoubleOrNull()
            val lon = xy[1].trim().toDoubleOrNull()
            if (lat == null || lon == null) null else GeoPoint(lat, lon)
        }
}

private const val REASON_SEPARATOR = "\n"

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

private fun point(lat: Double?, lon: Double?): GeoPoint? =
    if (lat != null && lon != null) GeoPoint(lat, lon) else null

// ---------- Perfis ----------

fun ProfileEntity.toModel() = FilterProfile(
    id = id,
    name = name,
    minPerKm = minPerKm,
    goodPerKm = goodPerKm,
    minPerHour = minPerHour,
    goodPerHour = goodPerHour,
    minPrice = minPrice,
    maxPickupKm = maxPickupKm,
    maxPickupMin = maxPickupMin,
    minRating = minRating,
    active = active,
)

fun FilterProfile.toEntity() = ProfileEntity(
    id = id,
    name = name,
    minPerKm = minPerKm,
    goodPerKm = goodPerKm,
    minPerHour = minPerHour,
    goodPerHour = goodPerHour,
    minPrice = minPrice,
    maxPickupKm = maxPickupKm,
    maxPickupMin = maxPickupMin,
    minRating = minRating,
    active = active,
)

// ---------- Zonas ----------

fun ZoneEntity.toModel(): Zone {
    val zoneType = enumOrNull<ZoneType>(type) ?: ZoneType.CUSTOM
    return Zone(
        id = id,
        name = name,
        type = zoneType,
        polygon = PolygonCodec.decode(polygon),
        colorArgb = colorArgb,
        alertEnabled = alertEnabled,
    )
}

fun Zone.toEntity() = ZoneEntity(
    id = id,
    name = name,
    type = type.name,
    polygon = PolygonCodec.encode(polygon),
    colorArgb = colorArgb,
    alertEnabled = alertEnabled,
)

// ---------- Ofertas ----------

fun EvaluatedOffer.toEntity(accepted: Boolean? = null) = OfferEntity(
    platform = raw.platform.name,
    price = raw.price,
    tripKm = raw.tripKm,
    tripMin = raw.tripMin,
    pickupKm = raw.pickupKm,
    pickupMin = raw.pickupMin,
    pickupAddress = raw.pickupAddress,
    dropoffAddress = raw.dropoffAddress,
    passengerRating = raw.passengerRating,
    totalKm = metrics.totalKm,
    totalMin = metrics.totalMin,
    perKm = metrics.perKm,
    perHour = metrics.perHour,
    netProfit = metrics.netProfit,
    netPerKm = metrics.netPerKm,
    signal = signal.name,
    reasons = reasons.joinToString(REASON_SEPARATOR),
    pickupLat = pickup?.lat,
    pickupLon = pickup?.lon,
    dropoffLat = dropoff?.lat,
    dropoffLon = dropoff?.lon,
    capturedAt = raw.capturedAt,
    accepted = accepted,
    rawText = raw.rawText,
)

/** Reconstrói a oferta. zoneHits não é persistido e volta vazio. */
fun OfferEntity.toRecord(): OfferRecord {
    val raw = RawOffer(
        platform = enumOrNull<Platform>(platform) ?: Platform.UBER,
        price = price,
        tripKm = tripKm,
        tripMin = tripMin,
        pickupKm = pickupKm,
        pickupMin = pickupMin,
        pickupAddress = pickupAddress,
        dropoffAddress = dropoffAddress,
        passengerRating = passengerRating,
        rawText = rawText,
        capturedAt = capturedAt,
    )
    val evaluated = EvaluatedOffer(
        raw = raw,
        // Gravadas com trecho incompleto contado como zero (R$/h absurdo): refaz com a estimativa.
        metrics = OfferEvaluator.repaired(raw, OfferMetrics(totalKm, totalMin, perKm, perHour, netProfit, netPerKm)),
        signal = enumOrNull<Signal>(signal) ?: Signal.YELLOW,
        reasons = if (reasons.isEmpty()) emptyList() else reasons.split(REASON_SEPARATOR),
        pickup = point(pickupLat, pickupLon),
        dropoff = point(dropoffLat, dropoffLon),
        zoneHits = emptyList(),
    )
    return OfferRecord(id = id, evaluated = evaluated, accepted = accepted)
}

// ---------- Finanças ----------

fun FinanceEntryEntity.toModel() = FinanceEntry(
    id = id,
    kind = enumOrNull<EntryKind>(kind) ?: EntryKind.EXPENSE,
    category = category,
    amount = amount,
    platform = enumOrNull<Platform>(platform),
    km = km,
    note = note,
    atMillis = atMillis,
)

fun FinanceEntry.toEntity() = FinanceEntryEntity(
    id = id,
    kind = kind.name,
    category = category,
    amount = amount,
    platform = platform?.name,
    km = km,
    note = note,
    atMillis = atMillis,
)
