package br.com.mapp.finance

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatação pt-BR (sem Android, testável em JVM). Valores ausentes viram "—". */
object Format {
    val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    const val NONE = "—"

    private val symbols = DecimalFormatSymbols(PT_BR)
    private val money = ThreadLocal.withInitial { DecimalFormat("#,##0.00", symbols) }
    private val oneDecimal = ThreadLocal.withInitial { DecimalFormat("#,##0.0", symbols) }
    private val threeDecimals = ThreadLocal.withInitial { DecimalFormat("#,##0.000", symbols) }
    private val plain = ThreadLocal.withInitial { DecimalFormat("0.##", symbols) }
    private val time = DateTimeFormatter.ofPattern("HH:mm", PT_BR)
    private val dayTime = DateTimeFormatter.ofPattern("dd/MM HH:mm", PT_BR)
    private val day = DateTimeFormatter.ofPattern("dd/MM", PT_BR)

    /** "R$ 1.234,56" / "-R$ 12,00". */
    fun brl(value: Double): String {
        val abs = money.get()!!.format(kotlin.math.abs(value))
        return if (value < 0 && abs != "0,00") "-R$ $abs" else "R$ $abs"
    }

    fun brl(value: Double?): String = value?.let { brl(it) } ?: NONE

    /** "12,3 km". */
    fun km(value: Double?): String = value?.let { "${oneDecimal.get()!!.format(it)} km" } ?: NONE

    /** "R$ 2,15/km". */
    fun perKm(value: Double?): String = value?.let { "${brl(it)}/km" } ?: NONE

    /**
     * Custo por km com 3 casas ("R$ 0,083/km"): carro elétrico gasta menos de 1 centavo a mais por km
     * a cada R$ 0,10 no kWh, e com 2 casas a mudança não aparecia.
     */
    fun costPerKm(value: Double?): String = value?.let { "R$ ${threeDecimals.get()!!.format(it)}/km" } ?: NONE

    /** "R$ 45,00/h". */
    fun perHour(value: Double?): String = value?.let { "${brl(it)}/h" } ?: NONE

    fun minutes(value: Int?): String = value?.let { "$it min" } ?: NONE

    /** Número para campo de edição: "6,5", "11", "" para null. */
    fun input(value: Double?): String = value?.let { plain.get()!!.format(it) } ?: ""

    /**
     * Lê número digitado ("1.234,56", "6,5", "6.5", "R$ 10"). Vazio ou inválido = null.
     * Com vírgula, pontos são separadores de milhar; sem vírgula, o ponto é decimal.
     */
    fun parse(text: String): Double? {
        val t = text.replace("R$", "").replace(" ", "").replace(" ", "").trim()
        if (t.isEmpty()) return null
        val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun dayTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun day(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        day.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Rótulos curtos de dia da semana, índice 0 = segunda (igual a ReportMath.incomeByWeekday). */
    val WEEKDAYS = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
}
