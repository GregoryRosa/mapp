package br.com.mapp.core.goal

import java.util.Locale

/**
 * Meta diária no anel do ícone "M": valor bruto do dia ÷ meta diária (a mesma de Ganhos e gastos).
 * O anel enche até 100%; ao bater, toca o som de dinheiro uma vez por dia e por valor de meta.
 */
object DayGoal {

    /** Meio centavo: soma de corridas em ponto flutuante não pode deixar a meta "quase" batida. */
    private const val EPS = 0.005

    /** Meta válida (null ou ≤ 0 = sem meta). */
    fun valid(goal: Double?): Double? = goal?.takeIf { it > 0 }

    fun reached(gross: Double, goal: Double?): Boolean = valid(goal)?.let { gross >= it - EPS } ?: false

    /** Quanto do anel está cheio (0..1); null = sem meta (anel só com a cor de status). */
    fun fraction(gross: Double, goal: Double?): Double? {
        val g = valid(goal) ?: return null
        return if (reached(gross, g)) 1.0 else (gross / g).coerceIn(0.0, 1.0)
    }

    /** Marca da comemoração: dia + valor da meta (meta nova no mesmo dia comemora de novo). */
    fun mark(day: String, goal: Double): String = "$day|" + String.format(Locale.ROOT, "%.2f", goal)

    /**
     * Comemorar agora? Devolve a marca a salvar, ou null. Só com a meta batida e se esta meta ainda
     * não foi comemorada hoje — desfazer uma corrida e bater de novo não repete o som.
     */
    fun celebration(gross: Double, goal: Double?, day: String, lastMark: String?): String? {
        val g = valid(goal) ?: return null
        if (!reached(gross, g)) return null
        return mark(day, g).takeIf { it != lastMark }
    }
}
