package br.com.mapp.reader.parser

import br.com.mapp.core.model.PointRole
import java.text.Normalizer

/** Valor numérico encontrado em um texto; [start, end) são posições no texto recebido. */
data class Measure(val value: Double, val start: Int, val end: Int)

/** Endereço extraído de uma linha. [role] vem de um rótulo explícito ("Destino: ..."), se houver. */
data class LabeledAddress(val address: String, val role: PointRole?)

/**
 * Funções puras (sem Android) para ler valores de textos de ofertas em pt-BR.
 *
 * CALIBRAÇÃO: todos os padrões abaixo foram escritos a partir de formatos genéricos. Ao capturar
 * textos reais (OfferAccessibilityService.debugCapture = true), transforme cada caso novo em teste
 * em ParseUtilsTest/OfferParsersTest antes de ajustar uma regex.
 */
object ParseUtils {

    // ---------------------------------------------------------------- normalização

    private val SPACES = Regex("[\\s\\u00A0\\u2007\\u202F]+")
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\uFE0F]")
    private val COMBINING = Regex("\\p{Mn}+")

    /** Remove caracteres invisíveis, troca espaços especiais (NBSP etc.) por espaço simples e apara. */
    fun normalize(text: String): String = text.replace(INVISIBLE, "").replace(SPACES, " ").trim()

    // Linha só com separador ("•", "·", "|"): a Uber às vezes entrega "6 min", "•", "1.6 km" em nós separados.
    private val SEPARATOR_ONLY = Regex("""^[\s·•|,\-–—/~:()\[\]]+$""")
    private val CURRENCY_ONLY = Regex("""^R\$$""", RegexOption.IGNORE_CASE)
    private val NUMBER_ONLY = Regex("""^\d[\d.,]*$""")

    /**
     * Quebra cada texto em linhas, normaliza e descarta vazias e separadores soltos.
     * Junta "R$" + "24,54" quando vêm em nós separados.
     */
    fun splitLines(texts: List<String>): List<String> {
        val raw = texts.flatMap { it.split('\n', '\r') }.map(::normalize)
            .filter { it.isNotEmpty() && !SEPARATOR_ONLY.matches(it) }
        val out = ArrayList<String>(raw.size)
        var i = 0
        while (i < raw.size) {
            val cur = raw[i]
            val next = raw.getOrNull(i + 1)
            if (CURRENCY_ONLY.matches(cur) && next != null && NUMBER_ONLY.matches(next)) {
                out += "R$ $next"
                i += 2
            } else {
                out += cur
                i++
            }
        }
        return out
    }

    /** Minúsculas e sem acentos — só para comparar palavras-chave. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING, "").lowercase()

    /**
     * Converte "12,50", "12.50", "1.234,56", "1,234.56" em Double.
     * [allowGrouping]: se true, um único separador seguido de exatamente 3 dígitos é tratado como
     * milhar ("R$ 1.234" = 1234). Use só para dinheiro; distâncias/notas tratam como decimal.
     */
    fun parseNumber(raw: String, allowGrouping: Boolean = false): Double? {
        val s = raw.trim().replace(" ", "")
        if (s.none { it.isDigit() }) return null
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val plain = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val decimal = if (lastDot > lastComma) '.' else ','
                val group = if (decimal == '.') "," else "."
                s.replace(group, "").replace(decimal, '.')
            }
            lastDot >= 0 || lastComma >= 0 -> {
                val sep = if (lastDot >= 0) '.' else ','
                val count = s.count { it == sep }
                val digitsAfter = s.length - s.lastIndexOf(sep) - 1
                when {
                    allowGrouping && (count > 1 || digitsAfter == 3) -> s.replace(sep.toString(), "")
                    count > 1 -> return null
                    else -> s.replace(sep, '.')
                }
            }
            else -> s
        }
        return plain.toDoubleOrNull()
    }

    // ---------------------------------------------------------------- dinheiro

    // "R$ 12,50", "R$12.50", "R$ 1.234,56", "R$ 1,234.56", "R$ 18".
    private val MONEY = Regex(
        """R\$\s*(\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    fun findMoney(text: String): List<Measure> = MONEY.findAll(text).mapNotNull { m ->
        parseNumber(m.groupValues[1], allowGrouping = true)?.let { Measure(it, m.range.first, m.range.last + 1) }
    }.toList()

    /** Primeiro valor em R$ do texto, ou null. */
    fun parseMoney(text: String): Double? = findMoney(normalize(text)).firstOrNull()?.value

    fun hasMoney(text: String): Boolean = MONEY.containsMatchIn(text)

    private val MONEY_ONLY_REST = Regex("""^[\s~≈·•|()\-–—/a-]*$""")

    /** true se o texto é basicamente só o preço ("R$ 18,40", "~R$ 18,40", "R$ 18 - R$ 22"). */
    fun isMoneyOnly(text: String): Boolean {
        val t = normalize(text)
        if (!hasMoney(t)) return false
        return MONEY_ONLY_REST.matches(t.replace(MONEY, ""))
    }

    // ---------------------------------------------------------------- distância

    // "1,2 km", "12.5km", "850 m", "850 metros". Não casa com "min" nem com "SP-280 km".
    private val DISTANCE = Regex(
        """(?<![\p{L}\d.,\-])(\d+(?:[.,]\d+)?)\s*(km|quil[ôo]metros?|metros?|m)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )

    /** Distâncias do texto, sempre em km (metros são convertidos). */
    fun findDistances(text: String): List<Measure> = DISTANCE.findAll(text).mapNotNull { m ->
        val n = parseNumber(m.groupValues[1]) ?: return@mapNotNull null
        val unit = m.groupValues[2].lowercase()
        val km = if (unit == "km" || unit.startsWith("quil")) n else n / 1000.0
        Measure(km, m.range.first, m.range.last + 1)
    }.toList()

    /** Primeira distância do texto em km, ou null. */
    fun parseDistanceKm(text: String): Double? = findDistances(normalize(text)).firstOrNull()?.value

    // ---------------------------------------------------------------- duração

    private const val NOT_AFTER_NUMBER = """(?<![\p{L}\d.,])"""
    private const val HOUR_UNIT = """(?:h|hr|hrs|hora|horas)"""
    private const val MIN_UNIT = """(?:min|mins|minuto|minutos)"""

    // "1 h 5 min", "1h05", "2 horas e 10 minutos"
    private val DUR_HM = Regex(
        NOT_AFTER_NUMBER + """(\d{1,2})\s*""" + HOUR_UNIT + """\.?\s*(?:e\s+)?(?:(\d{1,2})\s*""" + MIN_UNIT +
            """\.?|(\d{2}))(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )
    // "1 h", "2 horas"
    private val DUR_H = Regex(NOT_AFTER_NUMBER + """(\d{1,2})\s*""" + HOUR_UNIT + """(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    // "12 min", "12min", "12 minutos"
    private val DUR_M = Regex(NOT_AFTER_NUMBER + """(\d{1,3})\s*""" + MIN_UNIT + """\.?(?![\p{L}\d])""", RegexOption.IGNORE_CASE)

    // "há 3 min" / "3 min atrás" = há quanto tempo o pedido foi criado (inDrive), não é duração.
    private val AGO_BEFORE = Regex("""(?:^|\s)h[aá]\s*$""", RegexOption.IGNORE_CASE)
    private val AGO_AFTER = Regex("""^\s*atr[aá]s""", RegexOption.IGNORE_CASE)

    /** Durações do texto em minutos (Measure.value inteiro). */
    fun findDurations(text: String): List<Measure> {
        val found = mutableListOf<Measure>()
        fun free(r: IntRange) = found.none { r.first < it.end && it.start <= r.last }
        fun add(r: IntRange, minutes: Int) {
            if (!free(r)) return
            if (AGO_BEFORE.containsMatchIn(text.substring(0, r.first))) return
            if (AGO_AFTER.containsMatchIn(text.substring(r.last + 1))) return
            found += Measure(minutes.toDouble(), r.first, r.last + 1)
        }
        DUR_HM.findAll(text).forEach { m ->
            val h = m.groupValues[1].toInt()
            val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 0
            add(m.range, h * 60 + min)
        }
        DUR_H.findAll(text).forEach { m -> add(m.range, m.groupValues[1].toInt() * 60) }
        DUR_M.findAll(text).forEach { m -> add(m.range, m.groupValues[1].toInt()) }
        return found.sortedBy { it.start }
    }

    /** Primeira duração do texto em minutos, ou null. */
    fun parseDurationMin(text: String): Int? = findDurations(normalize(text)).firstOrNull()?.value?.toInt()

    // ---------------------------------------------------------------- nota do passageiro

    private const val STAR = "[\\u2605\\u2606\\u2B50\\u272A-\\u2730]"
    private val RATING_AFTER_STAR = Regex(STAR + """\s*([1-5](?:[.,]\d{1,2})?)(?![\d.,])""")
    private val RATING_BEFORE_STAR = Regex("""(?<![\d.,])([1-5](?:[.,]\d{1,2})?)\s*""" + STAR)
    private val RATING_KEYWORD = Regex(
        """(?:nota|avalia[çc][ãa]o|rating)\s*:?\s*([1-5][.,]\d{1,2})(?![\d.,])""",
        RegexOption.IGNORE_CASE,
    )
    // Texto que é só "4,93" ou "4,93 (120)". Exige 2 casas para não confundir com "1,2" solto.
    private val RATING_ALONE = Regex("""^([1-5][.,]\d{2})(?:\s*\(\d+\))?$""")

    /** Nota do passageiro (1,0–5,0): "★ 4,92", "4,9 ★ (213)", "Nota 4,93", "4,93". */
    fun parseRating(text: String): Double? {
        val t = normalize(text)
        for (re in listOf(RATING_AFTER_STAR, RATING_BEFORE_STAR, RATING_KEYWORD, RATING_ALONE)) {
            val v = re.find(t)?.groupValues?.get(1)?.let { parseNumber(it) } ?: continue
            if (v in 1.0..5.0) return v
        }
        return null
    }

    // ---------------------------------------------------------------- endereço

    // Tipos de logradouro / lugares comuns no início do endereço. CALIBRAR com textos reais.
    private val STREET_PREFIX = Regex(
        """^(?:(?:rua|r|av|avenida|al|alameda|tv|trav|travessa|estr|estrada|rod|rodovia|pç|pc|praça|praca|largo|lgo|""" +
            """viela|beco|via|servidão|servidao|passagem|ladeira|parque|pq|quadra|qd|setor|conj|conjunto|""" +
            """condomínio|condominio|cond|residencial|jardim|jd|shopping|aeroporto|terminal|estação|estacao|""" +
            """hospital)\.?\s|(?:br|sp|rj|mg|pr|rs|sc|ba|go|pe|ce|df)-\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val DASH_SEPARATOR = Regex("""\s[-–—]\s""")
    private val WORD = Regex("""\p{L}{2,}""")

    // Palavras (já sem acento) que indicam botão/rótulo de UI, nunca endereço. CALIBRAR.
    private val NON_ADDRESS_WORDS = listOf(
        "aceitar", "aceite", "recusar", "ignorar", "dinheiro", "pix", "cartao", "pagamento", "dinamic",
        "promo", "taxa", "inclu", "verificad", "avaliac", "perfil", "mensagem", "ligar", "cancelar",
        "oferecer", "ganhos", "segundos", "passageiro",
    )

    private val PICKUP_LABEL = Regex(
        """^(?:embarque|origem|partida|retirada|coleta|buscar em|de)\s*[:\-–]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val DROPOFF_LABEL = Regex(
        """^(?:destino|desembarque|para|entrega|chegada)\s*[:\-–]\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun hasStreetPrefix(text: String): Boolean = STREET_PREFIX.containsMatchIn(normalize(text))

    /**
     * Heurística: parece endereço? Precisa ter letras, não ter preço/distância/tempo/nota e
     * começar com tipo de logradouro ("Rua", "Av.", ...) ou ter vírgula / " - " (rua, número - bairro).
     * [blocked]: textos exatos a ignorar (ex.: nomes de categoria "UberX", "99Pop").
     */
    fun looksLikeAddress(text: String, blocked: Collection<String> = emptyList()): Boolean {
        val t = normalize(text)
        if (t.length !in 5..160) return false
        if (t.count { it.isLetter() } < 3) return false
        if (hasMoney(t) || findDistances(t).isNotEmpty() || findDurations(t).isNotEmpty()) return false
        if (parseRating(t) != null) return false
        val folded = fold(t)
        if (blocked.any { fold(it) == folded }) return false
        if (NON_ADDRESS_WORDS.any { folded.contains(it) }) return false
        if (STREET_PREFIX.containsMatchIn(t)) return true
        if (WORD.findAll(t).count() < 2) return false
        return t.contains(',') || DASH_SEPARATOR.containsMatchIn(t)
    }

    /** Remove rótulos como "Destino:" e devolve o endereço com o papel indicado, se parecer endereço. */
    fun extractAddress(text: String, blocked: Collection<String> = emptyList()): LabeledAddress? {
        val t = normalize(text)
        val (role, rest) = when {
            PICKUP_LABEL.containsMatchIn(t) -> PointRole.PICKUP to t.replaceFirst(PICKUP_LABEL, "")
            DROPOFF_LABEL.containsMatchIn(t) -> PointRole.DROPOFF to t.replaceFirst(DROPOFF_LABEL, "")
            else -> null to t
        }
        return if (looksLikeAddress(rest, blocked)) LabeledAddress(rest.trim(), role) else null
    }
}
