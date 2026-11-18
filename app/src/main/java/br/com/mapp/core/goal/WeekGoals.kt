package br.com.mapp.core.goal

import br.com.mapp.core.repo.Goals
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Meta de ganhos **por dia da semana**. Cada dia pode ter a sua meta; o dia sem meta própria usa a
 * **meta geral** ([Goals.daily]). Ex.: seg/ter/qua R$ 250, qui R$ 300, sex R$ 400, sáb/dom R$ 1.000.
 *
 * Kotlin puro: a mesma regra vale na tela Financeiro (seção Hoje) e no anel do ícone "M".
 */
object WeekGoals {

    /** Segunda → domingo, a ordem usada nas telas. */
    val DAYS: List<DayOfWeek> = DayOfWeek.values().toList()

    /** Meta válida (null ou ≤ 0 = sem meta). */
    fun valid(goal: Double?): Double? = goal?.takeIf { it > 0 && it.isFinite() }

    /** Meta **própria** do dia (null = o dia usa a meta geral). */
    fun own(goals: Goals, day: DayOfWeek): Double? = valid(goals.byWeekday[day.value])

    /** Meta que vale no dia: a própria do dia da semana ou, sem ela, a geral. */
    fun goalFor(goals: Goals, day: DayOfWeek): Double? = own(goals, day) ?: valid(goals.daily)

    /** Meta que vale na data (pelo dia da semana dela). */
    fun goalFor(goals: Goals, date: LocalDate): Double? = goalFor(goals, date.dayOfWeek)

    /** Algum dia tem meta própria? (só a geral = como era antes). */
    fun hasOwn(goals: Goals): Boolean = DAYS.any { own(goals, it) != null }

    /**
     * Grava [goal] nos [days] escolhidos (uma faixa de dias de uma vez).
     * Valor null ou ≤ 0 **tira** a meta própria: o dia volta para a meta geral.
     */
    fun withDays(goals: Goals, days: Collection<DayOfWeek>, goal: Double?): Goals {
        if (days.isEmpty()) return goals
        val map = goals.byWeekday.toMutableMap()
        val value = valid(goal)
        days.forEach { d -> if (value == null) map.remove(d.value) else map[d.value] = value }
        return goals.copy(byWeekday = map.toSortedMap())
    }

    /** Dias seguidos com a mesma meta, para mostrar a semana em poucas linhas. */
    data class Group(val days: List<DayOfWeek>, val goal: Double?) {
        val label: String
            get() = when (days.size) {
                1 -> WeekGoals.short(days.first())
                2 -> "${WeekGoals.short(days.first())} e ${WeekGoals.short(days.last())}"
                else -> "${WeekGoals.short(days.first())} a ${WeekGoals.short(days.last())}"
            }
    }

    /** A semana em grupos: ["Seg a Qua" 250, "Qui" 300, "Sex" 400, "Sáb e Dom" 1000]. */
    fun groups(goals: Goals): List<Group> {
        val out = mutableListOf<Group>()
        var days = mutableListOf<DayOfWeek>()
        var goal: Double? = null
        DAYS.forEach { d ->
            val g = goalFor(goals, d)
            if (days.isEmpty() || g == goal) {
                days.add(d)
                goal = g
            } else {
                out.add(Group(days, goal))
                days = mutableListOf(d)
                goal = g
            }
        }
        if (days.isNotEmpty()) out.add(Group(days, goal))
        return out
    }

    fun short(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Seg"
        DayOfWeek.TUESDAY -> "Ter"
        DayOfWeek.WEDNESDAY -> "Qua"
        DayOfWeek.THURSDAY -> "Qui"
        DayOfWeek.FRIDAY -> "Sex"
        DayOfWeek.SATURDAY -> "Sáb"
        DayOfWeek.SUNDAY -> "Dom"
    }

    fun name(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Segunda"
        DayOfWeek.TUESDAY -> "Terça"
        DayOfWeek.WEDNESDAY -> "Quarta"
        DayOfWeek.THURSDAY -> "Quinta"
        DayOfWeek.FRIDAY -> "Sexta"
        DayOfWeek.SATURDAY -> "Sábado"
        DayOfWeek.SUNDAY -> "Domingo"
    }

    /** "1=250.00;4=300.00" (dia da semana = meta), em ordem, para guardar no aparelho. */
    fun encode(byWeekday: Map<Int, Double>): String = byWeekday.entries
        .filter { it.key in 1..7 && valid(it.value) != null }
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}=" + String.format(Locale.ROOT, "%.2f", it.value) }

    fun decode(text: String?): Map<Int, Double> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = sortedMapOf<Int, Double>()
        text.split(';').forEach { part ->
            val day = part.substringBefore('=', "").trim().toIntOrNull() ?: return@forEach
            val goal = valid(part.substringAfter('=', "").trim().toDoubleOrNull()) ?: return@forEach
            if (day in 1..7) out[day] = goal
        }
        return out
    }
}
