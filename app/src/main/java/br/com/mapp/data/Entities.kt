package br.com.mapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Entidades Room. Enums são gravados pelo nome (String) para não depender de TypeConverters
 * e sobreviver a mudanças de ordem nas enums de core.
 */

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val minPerKm: Double?,
    val goodPerKm: Double?,
    val minPerHour: Double?,
    val goodPerHour: Double?,
    val minPrice: Double?,
    val maxPickupKm: Double?,
    val maxPickupMin: Int?,
    val minRating: Double?,
    val active: Boolean,
    // Versão 2: faixa verde de todos os critérios (null = sem faixa amarela, igual à versão 1).
    val goodPrice: Double? = null,
    val goodRating: Double? = null,
    val goodPickupKm: Double? = null,
    val goodPickupMin: Int? = null,
)

/** [polygon] serializado como "lat,lon;lat,lon;..." (ver [PolygonCodec]). */
@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val polygon: String,
    val colorArgb: Long,
    val alertEnabled: Boolean,
)

/** Oferta avaliada achatada. [reasons] unidos por "\n". */
@Entity(tableName = "offers", indices = [Index(value = ["capturedAt"])])
data class OfferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val price: Double,
    val tripKm: Double?,
    val tripMin: Int?,
    val pickupKm: Double?,
    val pickupMin: Int?,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val passengerRating: Double?,
    val totalKm: Double?,
    val totalMin: Int?,
    val perKm: Double?,
    val perHour: Double?,
    val netProfit: Double?,
    val netPerKm: Double?,
    val signal: String,
    val reasons: String,
    val pickupLat: Double?,
    val pickupLon: Double?,
    val dropoffLat: Double?,
    val dropoffLon: Double?,
    val capturedAt: Long,
    val accepted: Boolean?,
    val rawText: String,
)

@Entity(tableName = "finance_entries", indices = [Index(value = ["atMillis"])])
data class FinanceEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val category: String,
    val amount: Double,
    val platform: String?,
    val km: Double?,
    val note: String?,
    val atMillis: Long,
)
