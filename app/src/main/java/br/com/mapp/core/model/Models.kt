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

/**
 * Tipo de energia do veículo. Elétrico usa kWh (preço R$/kWh, consumo km/kWh);
 * os demais usam litro. [suggestedPrice]/[suggestedKmPerUnit] preenchem o formulário ao trocar o tipo.
 */
enum class FuelType(
    val label: String,
    val unit: String,
    val suggestedPrice: Double,
    val suggestedKmPerUnit: Double,
) {
    GASOLINE("Gasolina", "l", 6.20, 11.0),
    ETHANOL("Etanol", "l", 4.30, 7.7),
    DIESEL("Diesel", "l", 6.10, 12.0),
    ELECTRIC("Elétrico", "kWh", 0.90, 6.5);

    val isElectric: Boolean get() = this == ELECTRIC
    val priceSuffix: String get() = "R$/$unit"
    val consumptionSuffix: String get() = "km/$unit"
}

/** Custo por km criado pelo usuário (ex.: pneus, seguro, aluguel do carro). */
data class ExtraKmCost(val name: String, val perKm: Double)

/**
 * Custos do veículo usados para o lucro líquido. Preço e consumo na unidade de [fuelType] (litro ou kWh).
 * [extraCosts] = custos por km adicionados pelo usuário, somados ao [costPerKm].
 */
data class VehicleCostConfig(
    val fuelType: FuelType = FuelType.GASOLINE,
    val fuelPricePerUnit: Double = 6.00,
    val kmPerUnit: Double = 11.0,
    val maintenancePerKm: Double = 0.15,
    val depreciationPerKm: Double = 0.20,
    val extraCosts: List<ExtraKmCost> = emptyList(),
) {
    val energyCostPerKm: Double
        get() = if (kmPerUnit > 0) fuelPricePerUnit / kmPerUnit else 0.0

    val extraPerKm: Double
        get() = extraCosts.sumOf { it.perKm }

    val costPerKm: Double
        get() = energyCostPerKm + maintenancePerKm + depreciationPerKm + extraPerKm
}

/** Grava/lê [ExtraKmCost] em texto (uma linha por custo). */
object ExtraKmCostCodec {
    private const val SEP = "\t"

    fun encode(costs: List<ExtraKmCost>): String =
        costs.joinToString("\n") { c ->
            listOf("v1", c.name.replace(Regex("[\t\n\r]"), " ").trim(), c.perKm).joinToString(SEP)
        }

    /** Linhas inválidas são ignoradas (não derrubam as outras). */
    fun decode(text: String?): List<ExtraKmCost> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { row ->
            val c = row.split(SEP)
            if (c.size < 3 || c[0] != "v1") return@mapNotNull null
            val perKm = c[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            ExtraKmCost(c[1], perKm)
        }
    }
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
    /** Passageiro a bordo (Uber: "Destino de <nome>"). */
    ON_TRIP,
    /** Corrida encerrada (Uber: "Como foi a viagem?" / "Avaliar usuário"). */
    FINISHED,
}

/**
 * Etapa lida na tela + primeiro nome do passageiro, em minúsculas e sem acento ("caio"), quando a
 * tela mostra (null = não leu). Nome novo = corrida nova, mesmo aceita durante outra viagem.
 */
data class TripEvent(val stage: TripStage, val passenger: String? = null)

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
