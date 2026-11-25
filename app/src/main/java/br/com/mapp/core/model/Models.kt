package br.com.mapp.core.model

import java.time.LocalDate
import java.time.YearMonth

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
    /** Tipo da corrida como mostrado ao usuário: "UberX", "Comfort", "Black", "Electric", "Pop"... */
    val category: String? = null,
    val rawText: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    /** Paradas no caminho entre a coleta e o destino final, em ordem (endereços lidos no cartão). */
    val stopAddresses: List<String> = emptyList(),
    /**
     * Nº de paradas no caminho anunciado no cartão ("1 parada", "Várias paradas" = 1). 0 = direto.
     * Pode ser maior que [stopAddresses] (cartão avisa a parada sem mostrar o endereço).
     */
    val stopCount: Int = 0,
)

/**
 * Perfil de filtro (ex.: "Dia", "Noite", "Aeroporto"). Valores null = critério desligado.
 * Cada critério tem duas marcas do semáforo (ver `core/filter/FilterCriterion`):
 * - mais é melhor (R$/km, R$/h, valor, nota, lucro líquido da corrida — valor − desgaste dos km −
 *   custo fixo por corrida): `min*` = amarelo a partir de (abaixo = vermelho), `good*` = verde a partir de;
 * - menos é melhor (busca km/min): `maxPickup*` = amarelo até (acima = vermelho),
 *   `goodPickup*` = verde até.
 * `good*` null = o critério não impede o verde (perfis antigos continuam com o mesmo semáforo).
 */
data class FilterProfile(
    val id: Long = 0,
    val name: String = "Padrão",
    val minPerKm: Double? = 1.50,
    val goodPerKm: Double? = 2.00,
    val minPerHour: Double? = 30.0,
    val goodPerHour: Double? = 45.0,
    val minPrice: Double? = null,
    /** Valor da corrida verde a partir de ([minPrice] = amarelo a partir de). */
    val goodPrice: Double? = null,
    val maxPickupKm: Double? = 4.0,
    /** Busca verde até este km ([maxPickupKm] = amarelo até; acima = vermelho). */
    val goodPickupKm: Double? = null,
    val maxPickupMin: Int? = null,
    /** Busca verde até estes minutos ([maxPickupMin] = amarelo até; acima = vermelho). */
    val goodPickupMin: Int? = null,
    val minRating: Double? = null,
    /** Nota do passageiro verde a partir de ([minRating] = amarelo a partir de). */
    val goodRating: Double? = null,
    /**
     * Lucro líquido da corrida (valor − desgaste dos km − custo fixo por corrida,
     * `OfferMetrics.netProfit`) amarelo a partir de; abaixo = vermelho.
     */
    val minProfit: Double? = null,
    /** Lucro líquido da corrida verde a partir de ([minProfit] = amarelo a partir de). */
    val goodProfit: Double? = null,
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

/**
 * Como um custo de Custos fixos entra na conta:
 * - [PER_KM] e [PER_MONTH] = **desgaste** (combustível, pneus, manutenção...): conta a cada km rodado.
 *   [PER_MONTH] é o total do mês dividido pelos km do mês da [KmBase].
 * - [FIXED_MONTH] = **custo fixo** (seguro, financiamento, IPVA...): não depende dos km; o total do
 *   mês é dividido pelas corridas do mês da [KmBase] e descontado a cada corrida finalizada.
 */
enum class CostUnit(val suffix: String, val label: String) {
    PER_KM("R$/km", "por km"),
    PER_MONTH("R$/mês", "por mês"),
    FIXED_MONTH("R$/mês", "fixo do mês"),
    ;

    /** true = custo fixo, descontado por corrida (não por km). */
    val isFixed: Boolean get() = this == FIXED_MONTH
}

/**
 * Base do mês que reparte os custos digitados pelo total do mês: [kmPerDay] (média por dia rodado) ×
 * [daysPerMonth] (dias rodados no mês) = km do mês, para o desgaste em R$/km; [ridesPerDay] ×
 * [daysPerMonth] = corridas do mês, para os custos fixos em R$ por corrida. [auto] = vem das jornadas
 * (`core/shift/KmAverages`, atualizada sozinha); false = digitado pelo motorista.
 */
data class KmBase(
    val kmPerDay: Double = 150.0,
    val daysPerMonth: Double = 26.0,
    val auto: Boolean = true,
    val ridesPerDay: Double = 20.0,
) {
    val monthKm: Double
        get() = if (kmPerDay > 0 && daysPerMonth > 0) kmPerDay * daysPerMonth else 0.0

    val monthRides: Double
        get() = if (ridesPerDay > 0 && daysPerMonth > 0) ridesPerDay * daysPerMonth else 0.0

    /** R$/km de [amount] digitado em [unit]. Custo do mês sem km na base = 0; custo fixo = 0 (é por corrida). */
    fun perKm(amount: Double, unit: CostUnit): Double = when (unit) {
        CostUnit.PER_KM -> amount
        CostUnit.PER_MONTH -> if (monthKm > 0) amount / monthKm else 0.0
        CostUnit.FIXED_MONTH -> 0.0
    }

    /** R$ por corrida de [amount]: só o custo fixo (total do mês ÷ corridas do mês); desgaste = 0. */
    fun perRide(amount: Double, unit: CostUnit): Double = when (unit) {
        CostUnit.FIXED_MONTH -> if (monthRides > 0) amount / monthRides else 0.0
        CostUnit.PER_KM, CostUnit.PER_MONTH -> 0.0
    }

    /** R$/mês de [amount] digitado em [unit] (R$/km × km do mês). */
    fun perMonth(amount: Double, unit: CostUnit): Double = when (unit) {
        CostUnit.PER_KM -> amount * monthKm
        CostUnit.PER_MONTH, CostUnit.FIXED_MONTH -> amount
    }
}

/**
 * Estado do rateio dos custos fixos (`core/shift/FixedPlan`), guardado junto dos custos mas fora do
 * formulário: [today] = R$ por corrida do dia de trabalho (o que falta pagar no mês ÷ corridas que
 * faltam); [since] = dia em que o rateio começou; [settled] = último mês fechado (somando o total).
 */
data class FixedLedger(
    val today: Double? = null,
    val since: LocalDate? = null,
    val settled: YearMonth? = null,
)

/** Custo criado pelo usuário (ex.: pneus, seguro, aluguel do carro): [amount] em [unit]. */
data class ExtraKmCost(val name: String, val amount: Double, val unit: CostUnit = CostUnit.PER_KM)

/**
 * Custos do veículo usados para o lucro líquido. Preço e consumo na unidade de [fuelType] (litro ou kWh).
 * **Desgaste** (combustível, manutenção, depreciação e custos extras por km/mês) vira R$/km — [costPerKm],
 * contado a cada km rodado. **Custos fixos** ([CostUnit.FIXED_MONTH]: seguro, financiamento...) viram
 * R$ por corrida — [fixedPerRide], descontado a cada corrida finalizada. [ledger] = rateio do mês (não
 * é editado na tela; gravado à parte).
 */
data class VehicleCostConfig(
    val fuelType: FuelType = FuelType.GASOLINE,
    val fuelPricePerUnit: Double = 6.00,
    val kmPerUnit: Double = 11.0,
    val maintenance: Double = 0.15,
    val depreciation: Double = 0.20,
    val extraCosts: List<ExtraKmCost> = emptyList(),
    val maintenanceUnit: CostUnit = CostUnit.PER_KM,
    val depreciationUnit: CostUnit = CostUnit.PER_KM,
    val kmBase: KmBase = KmBase(),
    val ledger: FixedLedger = FixedLedger(),
) {
    val energyCostPerKm: Double
        get() = if (kmPerUnit > 0) fuelPricePerUnit / kmPerUnit else 0.0

    val maintenancePerKm: Double
        get() = kmBase.perKm(maintenance, maintenanceUnit)

    val depreciationPerKm: Double
        get() = kmBase.perKm(depreciation, depreciationUnit)

    /** R$/km de um custo adicionado pelo usuário. */
    fun perKmOf(cost: ExtraKmCost): Double = kmBase.perKm(cost.amount, cost.unit)

    val extraPerKm: Double
        get() = extraCosts.sumOf { perKmOf(it) }

    /** R$ por corrida de um custo adicionado pelo usuário (só os fixos; desgaste = 0). */
    fun perRideOf(cost: ExtraKmCost): Double = kmBase.perRide(cost.amount, cost.unit)

    /** true = algum custo de desgaste foi digitado pelo total do mês (depende dos km da [kmBase]). */
    val usesMonthly: Boolean
        get() = maintenanceUnit == CostUnit.PER_MONTH || depreciationUnit == CostUnit.PER_MONTH ||
            extraCosts.any { it.unit == CostUnit.PER_MONTH }

    /** Custos fixos do mês somados (R$/mês). */
    val fixedMonthTotal: Double
        get() = listOf(maintenance to maintenanceUnit, depreciation to depreciationUnit)
            .plus(extraCosts.map { it.amount to it.unit })
            .filter { it.second.isFixed }
            .sumOf { it.first }

    val hasFixed: Boolean
        get() = maintenanceUnit.isFixed || depreciationUnit.isFixed || extraCosts.any { it.unit.isFixed }

    /** Desgaste: R$ por km rodado (combustível + manutenção + depreciação + extras de desgaste). */
    val costPerKm: Double
        get() = energyCostPerKm + maintenancePerKm + depreciationPerKm + extraPerKm

    /**
     * Custos fixos: R$ descontados a cada corrida finalizada — o do rateio do dia ([FixedLedger.today]:
     * o que falta pagar no mês ÷ corridas que faltam) ou, antes do primeiro rateio, [evenFixedPerRide].
     */
    val fixedPerRide: Double
        get() = if (!hasFixed) 0.0 else ledger.today ?: evenFixedPerRide

    /** Custos fixos divididos por igual: total do mês ÷ corridas do mês pela base. */
    val evenFixedPerRide: Double
        get() = kmBase.perRide(fixedMonthTotal, CostUnit.FIXED_MONTH)

    /** Custo de uma corrida de [km]: desgaste dos km + a parte dela nos custos fixos. */
    fun rideCost(km: Double): Double = km * costPerKm + fixedPerRide
}

/**
 * Grava/lê [ExtraKmCost] em texto (uma linha por custo). "v1" = versão antiga, sempre por km;
 * "v2" leva a unidade (de antes dos custos fixos por corrida); "v3" = atual.
 */
object ExtraKmCostCodec {
    private const val SEP = "\t"

    fun encode(costs: List<ExtraKmCost>): String =
        costs.joinToString("\n") { c ->
            listOf("v3", c.name.replace(Regex("[\t\n\r]"), " ").trim(), c.amount, c.unit.name).joinToString(SEP)
        }

    /**
     * Linhas inválidas são ignoradas (não derrubam as outras). Custo "por mês" gravado antes dos custos
     * fixos ("v2") com nome de custo fixo (seguro, financiamento, IPVA...) passa a ser fixo do mês,
     * uma vez só: depois de gravado como "v3", vale o que o motorista escolheu.
     */
    fun decode(text: String?): List<ExtraKmCost> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { row ->
            val c = row.split(SEP)
            if (c.size < 3) return@mapNotNull null
            val amount = c[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val unit = CostUnit.entries.firstOrNull { it.name == c.getOrNull(3) }
            when (c[0]) {
                "v1" -> ExtraKmCost(c[1], amount, CostUnit.PER_KM)
                "v2" -> {
                    unit ?: return@mapNotNull null
                    val migrated = if (unit == CostUnit.PER_MONTH && looksFixed(c[1])) CostUnit.FIXED_MONTH else unit
                    ExtraKmCost(c[1], amount, migrated)
                }
                "v3" -> ExtraKmCost(c[1], amount, unit ?: return@mapNotNull null)
                else -> null
            }
        }
    }

    private val FIXED_WORDS = Regex(
        "seguro|financ|parcela|prestac|consorcio|ipva|licenciament|dpvat|aluguel|locac|" +
            "rastreador|mensalidade|internet|celular|plano|garagem|documenta",
    )

    /** Nome de custo que não depende dos km (seguro, financiamento, IPVA, aluguel do carro...). */
    fun looksFixed(name: String): Boolean {
        val k = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase()
        return FIXED_WORDS.containsMatchIn(k)
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
    /**
     * Sem corrida: tela inicial esperando chamada (Uber: "Procurando viagens"). Corrida que ainda
     * estava aberta terminou sem a tela de fim ter sido lida (só pela imagem — o texto mistura a
     * tela de fundo).
     */
    IDLE,
}

/**
 * Etapa lida na tela + primeiro nome do passageiro, em minúsculas e sem acento ("caio"), quando a
 * tela mostra (null = não leu). Nome novo = corrida nova, mesmo aceita durante outra viagem.
 * [km] = distância que a tela mostra até a busca/destino ("2 min · 0,9 km"), para conferir se a
 * oferta contada combina com a corrida. [platform] = app da tela.
 */
data class TripEvent(
    val stage: TripStage,
    val passenger: String? = null,
    val km: Double? = null,
    /** App em que a tela foi lida: a tela inicial da Uber não fecha corrida da 99. */
    val platform: Platform? = null,
)

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
    /** Paradas no caminho geocodificadas, na ordem de [RawOffer.stopAddresses] (null = não localizada). */
    val stops: List<GeoPoint?> = emptyList(),
)
