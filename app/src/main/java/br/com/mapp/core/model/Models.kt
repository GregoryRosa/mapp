package br.com.mapp.core.model

/** Plataformas de corrida suportadas. [packageName] é o app lido pela Acessibilidade. */
enum class Platform(val label: String, val packageName: String) {
    UBER("Uber", "com.ubercab.driver"),
    NINETY_NINE("99", "com.app99.driver"),
    INDRIVE("inDrive", "sinet.startup.inDriver");

    companion object {
        fun fromPackage(pkg: String?): Platform? = entries.firstOrNull { it.packageName == pkg }
    }
}

data class GeoPoint(val lat: Double, val lon: Double)

/** Oferta como lida da tela, antes de qualquer cálculo. Campos ausentes = null. */
data class RawOffer(
    val platform: Platform,
    val price: Double,
    val tripKm: Double? = null,
    val tripMin: Int? = null,
    val pickupKm: Double? = null,
    val pickupMin: Int? = null,
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null,
    val passengerRating: Double? = null,
    /** Tipo da corrida como mostrado ao usuário: "UberX", "Comfort", "Black", "Elétrico", "Pop"... */
    val category: String? = null,
    val rawText: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
)

/** Perfil de filtro (ex.: "Dia", "Noite", "Aeroporto"). Valores null = critério desligado. */
data class FilterProfile(
    val id: Long = 0,
    val name: String = "Padrão",
    val minPerKm: Double? = 1.50,
    val goodPerKm: Double? = 2.00,
    val minPerHour: Double? = 30.0,
    val goodPerHour: Double? = 45.0,
    val minPrice: Double? = null,
    val maxPickupKm: Double? = 4.0,
    val maxPickupMin: Int? = null,
    val minRating: Double? = null,
    val active: Boolean = true,
)

/** Custos do veículo usados para o lucro líquido. */
data class VehicleCostConfig(
    val fuelPricePerLiter: Double = 6.00,
    val kmPerLiter: Double = 11.0,
    val maintenancePerKm: Double = 0.15,
    val depreciationPerKm: Double = 0.20,
) {
    val costPerKm: Double
        get() = (if (kmPerLiter > 0) fuelPricePerLiter / kmPerLiter else 0.0) +
            maintenancePerKm + depreciationPerKm
}

enum class ZoneType(val label: String, val defaultColorArgb: Long) {
    DANGER("Zona perigosa", 0x66E53935),
    HIGH_DEMAND("Alta demanda", 0x6643A047),
    CUSTOM("Personalizada", 0x661E88E5),
}

/** Área desenhada pelo usuário no mapa. [polygon] em ordem, sem repetir o 1º ponto no fim. */
data class Zone(
    val id: Long = 0,
    val name: String,
    val type: ZoneType,
    val polygon: List<GeoPoint>,
    val colorArgb: Long = type.defaultColorArgb,
    val alertEnabled: Boolean = true,
)

enum class Signal { GREEN, YELLOW, RED }

/** Etapa da corrida reconhecida na tela do app de corrida (depois da oferta). */
enum class TripStage {
    /** Corrida aceita: indo buscar o passageiro (Uber: "Encontro com <nome>"). */
    ACCEPTED,
    /** Corrida encerrada (Uber: "Como foi a viagem?" / "Avaliar usuário"). */
    FINISHED,
}

enum class PointRole { PICKUP, DROPOFF }

data class ZoneHit(val zone: Zone, val role: PointRole)

data class OfferMetrics(
    val totalKm: Double?,
    val totalMin: Int?,
    val perKm: Double?,
    val perHour: Double?,
    val netProfit: Double?,
    val netPerKm: Double?,
)

/** Resultado final mostrado no overlay e salvo no histórico. */
data class EvaluatedOffer(
    val raw: RawOffer,
    val metrics: OfferMetrics,
    val signal: Signal,
    val reasons: List<String>,
    val pickup: GeoPoint? = null,
    val dropoff: GeoPoint? = null,
    val zoneHits: List<ZoneHit> = emptyList(),
)
