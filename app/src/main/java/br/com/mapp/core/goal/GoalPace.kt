package br.com.mapp.core.goal

import br.com.mapp.core.repo.Goals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Divisão da meta mensal pelos dias e pelas semanas (seg a dom, como a semana de ganhos da Uber).
 * Cada dia pesa a meta dele ([WeekGoals.goalFor]: a do dia da semana ou a geral); sem metas por dia,
 * todos os dias pesam igual. Dia sem meta, com outras metas por dia definidas, pesa zero (folga).
 */
object GoalPlan {

    /** Algum dia tem meta (geral ou própria)? Sem isso, todos os dias pesam igual. */
    fun hasDayGoals(goals: Goals): Boolean = WeekGoals.valid(goals.daily) != null || WeekGoals.hasOwn(goals)

    /** Peso do dia na divisão da meta e no ritmo. */
    fun weight(goals: Goals, date: LocalDate): Double =
        if (hasDayGoals(goals)) WeekGoals.goalFor(goals, date) ?: 0.0 else 1.0

    /** Parte da meta mensal que cabe em [date] (null = sem meta mensal). */
    fun monthShare(goals: Goals, date: LocalDate): Double? {
        val monthly = WeekGoals.valid(goals.monthly) ?: return null
        val days = daysOf(YearMonth.from(date))
        val total = days.sumOf { weight(goals, it) }
        // Nenhum dia com meta no mês: divide igual.
        return if (total > 0) monthly * weight(goals, date) / total else monthly / days.size
    }

    /**
     * Meta do dia: com meta mensal, a parte dela que cabe no dia ([monthShare] — a mesma conta da
     * semana, pesando as metas de cada dia da semana); sem mensal, a meta do dia ([WeekGoals.goalFor]).
     * null = sem meta. Com metas por dia, dia sem meta própria nem geral pesa zero: fica sem meta.
     */
    fun dayGoal(goals: Goals, date: LocalDate): Double? =
        if (WeekGoals.valid(goals.monthly) != null) monthShare(goals, date)?.takeIf { it > 0 }
        else WeekGoals.goalFor(goals, date)

    /** De onde veio a meta da semana. */
    enum class WeekSource { MONTHLY, DAILY }

    data class WeekGoal(val amount: Double, val source: WeekSource)

    /**
     * Meta da semana que começa na segunda [monday]: soma das partes da meta mensal dos 7 dias (semana
     * que vira o mês soma a parte de cada mês). Sem meta mensal, soma das metas dos dias. null = nenhuma.
     */
    fun weekGoal(goals: Goals, monday: LocalDate): WeekGoal? {
        val days = weekDays(monday)
        if (WeekGoals.valid(goals.monthly) != null) {
            return WeekGoal(days.sumOf { monthShare(goals, it) ?: 0.0 }, WeekSource.MONTHLY)
        }
        val sum = days.sumOf { WeekGoals.goalFor(goals, it) ?: 0.0 }
        return if (sum > 0) WeekGoal(sum, WeekSource.DAILY) else null
    }

    /** Pedaço de uma semana dentro do mês, com a parte da meta mensal. [whole] = semana inteira no mês. */
    data class WeekSlice(val from: LocalDate, val to: LocalDate, val amount: Double) {
        val whole: Boolean get() = from.dayOfWeek == DayOfWeek.MONDAY && to.dayOfWeek == DayOfWeek.SUNDAY
    }

    /** A meta mensal dividida pelas semanas que tocam o mês (só a parte do mês; somam a meta). */
    fun monthWeeks(goals: Goals, month: YearMonth): List<WeekSlice> {
        if (WeekGoals.valid(goals.monthly) == null) return emptyList()
        return daysOf(month)
            .groupBy { mondayOf(it) }
            .map { (_, days) -> WeekSlice(days.first(), days.last(), days.sumOf { monthShare(goals, it) ?: 0.0 }) }
    }

    fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekDays(monday: LocalDate): List<LocalDate> = (0L until 7L).map { monday.plusDays(it) }

    fun daysOf(month: YearMonth): List<LocalDate> = (1..month.lengthOfMonth()).map { month.atDay(it) }
}

/** Previsão da meta de um período no ritmo atual. */
sealed interface GoalForecast {
    /** Meta já batida. */
    data object Reached : GoalForecast

    /** Ainda nada ganho no período para medir o ritmo. */
    data object NoPace : GoalForecast

    /** No ritmo atual a meta fica batida em [date]. [perDay] = média por dia até agora. */
    data class ReachOn(val date: LocalDate, val perDay: Double) : GoalForecast

    /**
     * No ritmo atual o período fecha em [projected], faltando [missing]. [neededPerDay] = quanto
     * precisa por dia nos [daysLeft] dias que restam, contando hoje (null sem dias).
     */
    data class Short(
        val projected: Double,
        val missing: Double,
        val perDay: Double,
        val neededPerDay: Double?,
        val daysLeft: Int,
    ) : GoalForecast
}

/**
 * Em que dia, no ritmo atual, a meta do período fica batida. A meta é de **lucro líquido** (o que sobra
 * depois do custo do carro e dos gastos lançados), então os valores podem ser negativos: dia com mais
 * custo que ganho puxa o ritmo para baixo. O ritmo é o lucro por "peso" dos dias já
 * rodados ([GoalPlan.weight]: com metas por dia, sábado de meta R$ 1.000 rende mais que segunda de
 * R$ 250; sem elas, todo dia igual) e vale para os dias que faltam.
 *
 * Hoje ainda está em andamento: o ritmo sai dos dias anteriores e hoje conta como um dia normal (ou o
 * que já foi ganho, se passou disso). No 1º dia rodado do período, o ritmo é o de hoje.
 */
object GoalPace {

    /**
     * [days] = dias do período, em ordem; [today] = dia de trabalho; [achieved] = lucro líquido do
     * período até agora, [todayAchieved] = parte dele feita hoje (pode ser negativa: só custo até agora).
     */
    fun forecast(
        goal: Double,
        achieved: Double,
        todayAchieved: Double,
        days: List<LocalDate>,
        today: LocalDate,
        weight: (LocalDate) -> Double,
    ): GoalForecast {
        if (achieved >= goal) return GoalForecast.Reached
        val inPeriod = today in days
        // Lucro de hoje (negativo = só custo até agora). O dos dias anteriores é o resto do período.
        val todayEarned = if (inPeriod) todayAchieved else 0.0
        val prev = days.filter { it < today }
        val future = days.filter { it > today }
        // Sem peso nenhum (dias sem meta, com metas por dia): todo dia pesa igual.
        val w: (LocalDate) -> Double =
            if (days.sumOf { weight(it).coerceAtLeast(0.0) } > 0) { d -> weight(d).coerceAtLeast(0.0) } else { _ -> 1.0 }

        val prevAchieved = (achieved - todayEarned).coerceAtLeast(0.0)
        val prevWeight = prev.sumOf(w)
        val ratio: Double
        val perDay: Double
        var total: Double
        if (prevAchieved > 0 && prevWeight > 0) {
            val usual = prevAchieved / prevWeight
            val todayUsual = if (inPeriod) usual * w(today) else 0.0
            if (todayEarned >= todayUsual) {
                // Hoje já rendeu o normal: entra no ritmo como dia inteiro.
                val counted = prev + listOfNotNull(today.takeIf { inPeriod })
                ratio = achieved / counted.sumOf(w).coerceAtLeast(1e-9)
                perDay = achieved / counted.size
                total = achieved
            } else {
                // Hoje ainda em andamento: fecha como um dia normal.
                ratio = usual
                perDay = prevAchieved / prev.size
                total = prevAchieved + todayUsual
                if (total >= goal) return GoalForecast.ReachOn(today, perDay)
            }
        } else if (achieved > 0) {
            // 1º dia com ganho: o ritmo inclui os dias parados antes dele.
            val counted = prev + listOfNotNull(today.takeIf { inPeriod })
            val countedWeight = counted.sumOf(w)
            if (counted.isEmpty() || countedWeight <= 0) return GoalForecast.NoPace
            ratio = achieved / countedWeight
            perDay = achieved / counted.size
            total = achieved
        } else {
            return GoalForecast.NoPace
        }

        for (d in future) {
            total += ratio * w(d)
            if (total >= goal - 0.005) return GoalForecast.ReachOn(d, perDay)
        }
        // Dias que restam para correr atrás, contando hoje (ainda dá para rodar mais).
        val left = future.size + if (inPeriod) 1 else 0
        return GoalForecast.Short(
            projected = total,
            missing = goal - total,
            perDay = perDay,
            neededPerDay = if (left > 0) (goal - achieved) / left else null,
            daysLeft = left,
        )
    }
}
