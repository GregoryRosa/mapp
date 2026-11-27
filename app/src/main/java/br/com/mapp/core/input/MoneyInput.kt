package br.com.mapp.core.input

import kotlin.math.roundToLong

/**
 * Campo de dinheiro no estilo maquininha (pedido do dono, 27/11/2026): só se digitam números e o
 * valor se forma da direita para a esquerda, em centavos — 1773 → "17,73", 5 → "0,05",
 * 100000 → "1.000,00". Vírgula dos centavos e ponto do milhar entram sozinhos; apagar tira o último
 * dígito; campo vazio continua vazio.
 *
 * O campo guarda só os **dígitos** (centavos); a tela vê o texto formatado ([format]), que o
 * `Format.parse` das telas já lê. Kotlin puro: o componente do Compose fica em `finance/FinanceUi`.
 */
object MoneyInput {
    /** Até R$ 9.999.999,99: dígito a mais é ignorado. */
    const val MAX_DIGITS = 9

    /**
     * Dígitos depois de uma edição: [new] é o texto que o teclado deixou (pode ter colado letras,
     * "R$", vírgula). Fica só com os números, sem zeros à esquerda ("0" se só digitou zeros);
     * passou de [MAX_DIGITS] = continua [old].
     */
    fun edit(old: String, new: String): String {
        val digits = new.filter { it in '0'..'9' }
        if (digits.isEmpty()) return ""
        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        return if (trimmed.length > MAX_DIGITS) old else trimmed
    }

    /** Dígitos (centavos) → "17,73" / "1.000,00"; vazio = "". */
    fun format(digits: String): String {
        val clean = digits.filter { it in '0'..'9' }.trimStart('0')
        if (digits.none { it in '0'..'9' }) return ""
        val cents = clean.ifEmpty { "0" }.padStart(3, '0')
        val reais = cents.dropLast(2)
        val grouped = reais.reversed().chunked(3).joinToString(".").reversed()
        return "$grouped,${cents.takeLast(2)}"
    }

    /** Valor em reais dos dígitos (null = vazio). */
    fun value(digits: String): Double? {
        val clean = digits.filter { it in '0'..'9' }
        if (clean.isEmpty()) return null
        return clean.toBigDecimal().movePointLeft(2).toDouble()
    }

    /** Valor salvo → dígitos para o campo (null, negativo ou inválido = vazio). */
    fun digitsOf(value: Double?): String {
        val v = value?.takeIf { it.isFinite() && it >= 0 } ?: return ""
        return (v * 100).roundToLong().toString()
    }

    /**
     * Texto que a tela guarda ("17,73", "17", "1.234,56", "6.5") → dígitos para o campo. Mesma
     * leitura do `Format.parse`: com vírgula, os pontos são milhar; sem vírgula, o ponto é decimal.
     */
    fun digitsOf(text: String): String = digitsOf(parse(text))

    private fun parse(text: String): Double? {
        val t = text.replace("R$", "").replace(" ", "").replace(" ", "").trim()
        if (t.isEmpty()) return null
        val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
