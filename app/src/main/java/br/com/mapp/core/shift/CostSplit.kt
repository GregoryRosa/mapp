package br.com.mapp.core.shift

import br.com.mapp.core.model.VehicleCostConfig
import java.text.Normalizer

/**
 * Um item do custo do carro (Financeiro → Custos fixos): combustível (ou energia), manutenção,
 * depreciação ou um custo adicionado pelo motorista (pneus, seguro, IPVA...). Desgaste em [perKm]
 * (R$/km; custo do mês já convertido pelos km da base); custo fixo em [fixedShare] (fração do valor
 * das corridas finalizadas; total do mês ÷ faturamento da base) — é o **peso** do item na divisão do
 * fixo. Jornada gravada quando o fixo era por corrida guarda R$ por corrida aqui: dentro da mesma
 * jornada a proporção entre os itens é a mesma.
 */
data class CostPart(val name: String, val perKm: Double, val fixedShare: Double = 0.0)

/** Quanto um custo somou no período, em R$. */
data class CostAmount(val name: String, val amount: Double)

/** Um custo do período com a fatia do ganho bruto que ele levou. [percent] = null sem bruto. */
data class CostShare(val name: String, val amount: Double, val percent: Double?)

/**
 * Para onde foi o bruto do período (100%): cada custo do carro (desgaste: km × R$/km; fixos: % do valor
 * das corridas — de cada item de Custos fixos), cada categoria de gasto lançado e a sobra (líquido),
 * tudo em R$ e em % do bruto.
 */
data class CostBreakdown(
    val gross: Double,
    /** Custos do carro, na ordem de Custos fixos. */
    val car: List<CostShare>,
    /** Gastos lançados à mão, por categoria (maior primeiro). */
    val expenses: List<CostShare>,
    /** Soma dos gastos lançados (linha "Gastos lançados"). */
    val expensesTotal: CostShare?,
    /** O que sobrou para o motorista: bruto − todos os custos. */
    val net: CostShare,
    /**
     * true = alguma jornada do período foi encerrada antes de o MApp guardar a divisão do custo/km:
     * o custo dela foi dividido pelas proporções dos Custos fixos de hoje.
     */
    val splitEstimated: Boolean,
    /**
     * Gastos lançados em "Combustível". O combustível já entra nos custos do carro (km × R$/km);
     * abastecimento lançado também conta de novo — o valor aparece para o motorista decidir.
     */
    val fuelLaunched: Double,
) {
    val isEmpty: Boolean get() = gross <= 0.0 && car.isEmpty() && expenses.isEmpty()
}

object CostSplit {
    const val FUEL = "Combustível"
    const val ENERGY = "Energia"
    const val MAINTENANCE = "Manutenção"
    const val DEPRECIATION = "Depreciação"
    const val EXTRA = "Outro custo"
    /** Custo do carro sem divisão conhecida (nenhum item com valor nos Custos fixos). */
    const val CAR = "Custo do carro"
    const val EXPENSES = "Gastos lançados"
    const val NET = "Sobra para você"

    /** Itens do custo do carro dos Custos fixos de agora, na ordem da tela. */
    fun parts(cfg: VehicleCostConfig): List<CostPart> = buildList {
        val base = cfg.kmBase
        add(CostPart(if (cfg.fuelType.isElectric) ENERGY else FUEL, cfg.energyCostPerKm))
        add(CostPart(MAINTENANCE, cfg.maintenancePerKm, base.share(cfg.maintenance, cfg.maintenanceUnit)))
        add(CostPart(DEPRECIATION, cfg.depreciationPerKm, base.share(cfg.depreciation, cfg.depreciationUnit)))
        cfg.extraCosts.forEach { add(CostPart(it.name.trim().ifEmpty { EXTRA }, cfg.perKmOf(it), cfg.shareOf(it))) }
    }

    /**
     * Divide [cost] (R$ de desgaste de um trecho: km × R$/km) pelos itens [parts], na proporção do
     * R$/km de cada um. Sem item com valor, tudo fica em [CAR].
     */
    fun split(cost: Double, parts: List<CostPart>): List<CostAmount> = splitBy(cost, parts) { it.perKm }

    /**
     * Divide [cost] (R$ dos custos fixos: % do valor das corridas finalizadas) pelos itens [parts], na
     * proporção do [CostPart.fixedShare] de cada um. Sem item fixo com valor, tudo fica em [CAR].
     */
    fun splitFixed(cost: Double, parts: List<CostPart>): List<CostAmount> = splitBy(cost, parts) { it.fixedShare }

    private fun splitBy(cost: Double, parts: List<CostPart>, weight: (CostPart) -> Double): List<CostAmount> {
        if (cost == 0.0) return emptyList()
        val weights = parts.filter { weight(it) > 0 && weight(it).isFinite() }
        val sum = weights.sumOf { weight(it) }
        if (sum <= 0) return listOf(CostAmount(CAR, cost))
        return weights.map { CostAmount(it.name, cost * weight(it) / sum) }
    }

    /** Soma por nome, mantendo a ordem em que cada nome apareceu primeiro. */
    fun merge(items: List<CostAmount>): List<CostAmount> {
        val out = LinkedHashMap<String, Double>()
        items.forEach { out[it.name] = (out[it.name] ?: 0.0) + it.amount }
        return out.map { (name, amount) -> CostAmount(name, amount) }
    }

    /**
     * Quebra os custos de [t] em fatias do bruto. Os custos do carro vêm de [PeriodTotals.carCostItems]
     * (divididos pelo [PeriodMath.totals]); sem eles, o custo do carro entra inteiro em [CAR].
     */
    fun breakdown(t: PeriodTotals): CostBreakdown {
        val gross = t.gross
        fun share(name: String, amount: Double) = CostShare(name, amount, percentOf(amount, gross))
        val carItems = if (t.carCostItems.isNotEmpty()) t.carCostItems
        else if (t.carCost > 0) listOf(CostAmount(CAR, t.carCost)) else emptyList()
        val expenses = t.expenseItems.filter { it.amount > 0 }.sortedByDescending { it.amount }
        return CostBreakdown(
            gross = gross,
            car = carItems.filter { it.amount > 0.004 }.map { share(it.name, it.amount) },
            expenses = expenses.map { share(it.name, it.amount) },
            expensesTotal = if (t.expenses > 0) share(EXPENSES, t.expenses) else null,
            net = share(NET, t.net),
            splitEstimated = t.splitEstimated,
            fuelLaunched = expenses.filter { isFuel(it.name) }.sumOf { it.amount },
        )
    }

    /** % de [amount] em [gross]; null sem bruto. */
    fun percentOf(amount: Double, gross: Double): Double? = if (gross > 0) amount * 100 / gross else null

    /** Categoria de gasto que é combustível/energia (abastecimento, recarga). */
    fun isFuel(category: String): Boolean {
        val k = Normalizer.normalize(category, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
        return k == "combustivel" || k == "energia" || k == "abastecimento" || k == "recarga"
    }

    // ---------- Gravação junto da jornada ----------

    private const val ITEM_SEP = '\u001F'
    private const val VALUE_SEP = '\u001D'

    /** Texto de uma linha só (sem tab/quebra) para a [ShiftCodec]. Vazio = sem divisão. */
    fun encode(parts: List<CostPart>?): String =
        parts.orEmpty().joinToString(ITEM_SEP.toString()) { p ->
            clean(p.name) + VALUE_SEP + p.perKm + if (p.fixedShare > 0) "$VALUE_SEP${p.fixedShare}" else ""
        }

    /**
     * null = sem divisão gravada (jornada de versão antiga). Itens ilegíveis são pulados. Item sem o
     * 3º valor (gravado antes dos custos fixos) = só desgaste. O 3º valor é o peso do fixo: fração do
     * valor (atual) ou R$ por corrida (versões anteriores) — os dois servem para dividir.
     */
    fun decode(text: String?): List<CostPart>? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(ITEM_SEP).mapNotNull { row ->
            val c = row.split(VALUE_SEP)
            if (c.size != 2 && c.size != 3) return@mapNotNull null
            val perKm = c[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val fixedShare = if (c.size == 3) {
                c[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            } else {
                0.0
            }
            CostPart(c[0], perKm, fixedShare)
        }
        return parts.ifEmpty { null }
    }

    private fun clean(name: String) =
        name.replace(Regex("[\t\n\r\u001F\u001D\u001E]"), " ").trim().ifEmpty { EXTRA }
}
