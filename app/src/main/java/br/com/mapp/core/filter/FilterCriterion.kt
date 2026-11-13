package br.com.mapp.core.filter

import br.com.mapp.core.model.FilterProfile
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Critérios do perfil de filtro, cada um com duas marcas do semáforo (amarelo e verde) guardadas
 * em dois campos do [FilterProfile]:
 * - [PER_KM] ↔ minPerKm/goodPerKm; [PER_HOUR] ↔ minPerHour/goodPerHour; [RATING] ↔ minRating/goodRating;
 *   [PRICE] ↔ minPrice/goodPrice (mais é melhor: low = mínimo, high = bom);
 * - [PICKUP_KM] ↔ goodPickupKm/maxPickupKm; [PICKUP_MIN] ↔ goodPickupMin/maxPickupMin (menos é
 *   melhor: low = bom, high = máximo).
 * Usado pelos sliders da tela Perfis e pelo painel rápido; a regra do semáforo está em
 * `core/engine/OfferEvaluator` e a cor de cada informação em `core/card/CardValues.tone`.
 */
enum class FilterCriterion(
    val title: String,
    val scale: ThresholdScale,
    /** true: mais é melhor (vermelho | amarelo | verde). false: menos é melhor (verde | amarelo | vermelho). */
    val higherIsBetter: Boolean,
    /** Faixa sugerida quando o perfil não tem o critério. */
    val default: ThresholdRange,
) {
    PER_KM("Valor por km", ThresholdScale.PER_KM, true, ThresholdRange(1.50, 2.00)),
    PER_HOUR("Valor por hora", ThresholdScale.PER_HOUR, true, ThresholdRange(30.0, 45.0)),
    RATING("Nota do passageiro", ThresholdScale.RATING, true, ThresholdRange(4.70, 4.90)),
    PRICE("Valor da corrida", ThresholdScale.MIN_PRICE, true, ThresholdRange(8.0, 12.0)),
    PICKUP_KM("Busca (km)", ThresholdScale.PICKUP_KM, false, ThresholdRange(2.0, 4.0)),
    PICKUP_MIN("Busca (minutos)", ThresholdScale.PICKUP_MIN, false, ThresholdRange(5.0, 8.0));

    /** Valores inteiros (minutos). */
    val integer: Boolean get() = this == PICKUP_MIN

    /**
     * Faixa salva no perfil (null = critério fora). Mais é melhor: low = amarelo a partir de,
     * high = verde a partir de. Menos é melhor: low = verde até, high = amarelo até.
     */
    fun range(p: FilterProfile): ThresholdRange? = when (this) {
        PER_KM -> ThresholdRange.of(p.minPerKm, p.goodPerKm)
        PER_HOUR -> ThresholdRange.of(p.minPerHour, p.goodPerHour)
        RATING -> ThresholdRange.of(p.minRating, p.goodRating)
        PRICE -> ThresholdRange.of(p.minPrice, p.goodPrice)
        PICKUP_KM -> ThresholdRange.ofMax(p.goodPickupKm, p.maxPickupKm)
        PICKUP_MIN -> ThresholdRange.ofMax(p.goodPickupMin?.toDouble(), p.maxPickupMin?.toDouble())
    }

    /** Perfil com a faixa (null tira o critério: os dois campos null). PICKUP_MIN arredonda (roundToInt). */
    fun apply(p: FilterProfile, r: ThresholdRange?): FilterProfile = when (this) {
        PER_KM -> p.copy(minPerKm = r?.low, goodPerKm = r?.high)
        PER_HOUR -> p.copy(minPerHour = r?.low, goodPerHour = r?.high)
        RATING -> p.copy(minRating = r?.low, goodRating = r?.high)
        PRICE -> p.copy(minPrice = r?.low, goodPrice = r?.high)
        PICKUP_KM -> p.copy(goodPickupKm = r?.low, maxPickupKm = r?.high)
        PICKUP_MIN -> p.copy(goodPickupMin = r?.low?.roundToInt(), maxPickupMin = r?.high?.roundToInt())
    }

    /**
     * Texto pt-BR: PER_KM "R$ 1,60"; PER_HOUR "R$ 40" (sem casas se inteiro, senão "R$ 42,5");
     * RATING "4,71"; PRICE "R$ 8,00"; PICKUP_KM "4,0 km"; PICKUP_MIN "8 min".
     */
    fun format(v: Double): String = when (this) {
        PER_KM -> "R$ " + num(v, 2)
        PER_HOUR -> "R$ " + num(v, 2).trimEnd('0').trimEnd(',')
        RATING -> num(v, 2)
        PRICE -> "R$ " + num(v, 2)
        PICKUP_KM -> num(v, 1) + " km"
        PICKUP_MIN -> "${v.roundToInt()} min"
    }

    private companion object {
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

        fun num(v: Double, decimals: Int): String = String.format(PT_BR, "%.${decimals}f", v)
    }
}
