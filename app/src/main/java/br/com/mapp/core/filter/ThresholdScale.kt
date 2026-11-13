package br.com.mapp.core.filter

import java.math.BigDecimal
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Escala de um slider de filtro (tela Perfis): de [min] a [max] em passos de [step]. Arrastar dá
 * valores no passo; valor digitado fica exato e, se cair fora da escala, [fitting] estica a escala
 * para o slider acompanhar.
 */
data class ThresholdScale(val min: Double, val max: Double, val step: Double) {

    /** Casas decimais do passo (0,05 → 2; 0,1 → 1; 1 → 0). */
    val decimals: Int get() = BigDecimal(step.toString()).stripTrailingZeros().scale().coerceAtLeast(0)

    /** Valor no passo mais próximo, dentro da escala. */
    fun snap(value: Double): Double {
        val n = ((value - min) / step).roundToLong()
        return round(min + n * step).coerceIn(min, max)
    }

    /** Posição do valor no slider (0 = começo, 1 = fim); fora da escala fica na ponta. */
    fun fraction(value: Double): Double =
        if (max <= min) 0.0 else ((value - min) / (max - min)).coerceIn(0.0, 1.0)

    /** Valor na posição [fraction] do slider (no passo). */
    fun valueAt(fraction: Double): Double = snap(min + fraction.coerceIn(0.0, 1.0) * (max - min))

    /** Escala que cabe [values] (valores digitados fora dela). Nunca começa abaixo de zero. */
    fun fitting(vararg values: Double?): ThresholdScale {
        val vs = values.filterNotNull().filter { it.isFinite() }
        if (vs.isEmpty()) return this
        // EPS: 3,55 / 0,01 dá 354,999… em ponto flutuante.
        val lo = minOf(min, round(floor(vs.min() / step + EPS) * step)).coerceAtLeast(0.0)
        val hi = maxOf(max, round(ceil(vs.max() / step - EPS) * step))
        return if (lo == min && hi == max) this else copy(min = lo, max = hi)
    }

    private fun round(v: Double): Double {
        val f = 10.0.pow(decimals)
        return (v * f).roundToLong() / f
    }

    companion object {
        private const val EPS = 1e-9

        val PER_KM = ThresholdScale(0.5, 4.0, 0.05)
        val PER_HOUR = ThresholdScale(10.0, 120.0, 1.0)
        val RATING = ThresholdScale(4.0, 5.0, 0.01)
        val MIN_PRICE = ThresholdScale(0.0, 40.0, 0.5)
        val PICKUP_KM = ThresholdScale(0.5, 12.0, 0.1)
        val PICKUP_MIN = ThresholdScale(1.0, 30.0, 1.0)
    }
}

/**
 * Faixa amarelo/verde de um critério, sempre com [low] ≤ [high]. Mais é melhor (R$/km, nota...):
 * abaixo de [low] = vermelho, de [low] até [high] = amarelo, a partir de [high] = verde. Menos é
 * melhor (busca — [ofMax]): até [low] = verde, até [high] = amarelo, acima = vermelho. Uma marca
 * nunca passa da outra: mover uma empurra a outra.
 */
data class ThresholdRange(val low: Double, val high: Double) {
    fun withLow(value: Double) = ThresholdRange(value, maxOf(high, value))

    fun withHigh(value: Double) = ThresholdRange(minOf(low, value), value)

    companion object {
        /**
         * Lê o mínimo/bom do perfil. Só o mínimo: "bom" igual a ele (sem faixa amarela, mesmo
         * semáforo). Só o bom: mínimo zero (nada fica vermelho, mesmo semáforo). Nenhum = desligado.
         */
        fun of(low: Double?, high: Double?): ThresholdRange? = when {
            low == null && high == null -> null
            low == null -> ThresholdRange(0.0, high!!)
            high == null -> ThresholdRange(low, low)
            else -> ThresholdRange(minOf(low, high), high)
        }

        /**
         * Critério "quanto menos melhor" (busca): [good] = verde até, [max] = amarelo até (vira
         * [low]/[high]). Só o máximo: "bom" igual a ele (sem faixa amarela, mesmo semáforo). Só o
         * bom: máximo igual a ele. Nenhum = desligado.
         */
        fun ofMax(good: Double?, max: Double?): ThresholdRange? = when {
            good == null && max == null -> null
            good == null -> ThresholdRange(max!!, max)
            max == null -> ThresholdRange(good, good)
            else -> ThresholdRange(minOf(good, max), max)
        }
    }
}
