package br.com.mapp.core.card

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.route.RideRoute
import java.text.Normalizer
import java.util.Locale
import kotlin.math.pow

/**
 * Informação do Card que um filtro olha (o dropdown da aba Card → Filtros). [ADDRESS] compara
 * palavras; as outras comparam o número que o Card mostra, com [decimals] casas (as mesmas do Card).
 */
enum class FilterField(val id: String, val label: String, val unit: String, val decimals: Int) {
    ADDRESS("address", "Endereço", "", 0),
    PRICE("price", "Valor da corrida", "R$", 2),
    PER_KM("per_km", "R$/Km", "R$", 2),
    PER_HOUR("per_hour", "R$/Hora", "R$", 0),
    PER_MIN("per_min", "R$/Min", "R$", 2),
    NET_PROFIT("net", "Lucro (R$)", "R$", 2),
    NET_PER_HOUR("net_hour", "Lucro/Hora", "R$", 0),
    NET_PERCENT("net_pct", "Lucro %", "%", 0),
    RATING("rating", "Nota", "★", 2),
    PICKUP_KM("pickup_km", "Busca (km)", "km", 1),
    PICKUP_MIN("pickup_min", "Busca (min)", "min", 0),
    TRIP_KM("trip_km", "Viagem (km)", "km", 1),
    TRIP_MIN("trip_min", "Viagem (min)", "min", 0),
    TOTAL_KM("total_km", "Distância total (km)", "km", 1),
    TOTAL_MIN("total_min", "Tempo total (min)", "min", 0),
    STOPS("stops", "Paradas no caminho", "", 0);

    /** Filtro de palavras (endereço); os outros são de número (de/até). */
    val isText: Boolean get() = this == ADDRESS

    companion object {
        fun fromId(id: String?): FilterField? = entries.firstOrNull { it.id == id }
    }
}

/** Em qual endereço as palavras do filtro valem. [ANY] inclui as paradas no caminho. */
enum class AddressScope(val id: String, val label: String) {
    ANY("any", "Coleta ou destino"),
    PICKUP("pickup", "Só coleta"),
    DROPOFF("dropoff", "Só destino");

    companion object {
        fun fromId(id: String?): AddressScope = entries.firstOrNull { it.id == id } ?: ANY
    }
}

/**
 * Filtro criado pelo usuário em Card → Filtros: quando a corrida casa, o Card ganha a etiqueta com o
 * [name] na [color] (ARGB) e, com [paint], o fundo do Card fica dessa cor (no lugar do semáforo; as
 * barrinhas de cada número continuam com o semáforo). [speak] = a voz diz o nome.
 * - [item] de endereço: casa se alguma das [words] aparece no endereço ([scope]);
 * - [item] de número: casa se o valor está entre [min] e [max] (um dos dois pode ficar vazio).
 * O filtro só **mostra**: nunca aceita nem recusa corrida.
 */
data class CardFilter(
    val id: Long,
    val name: String = "",
    val color: Int = CardFilters.PALETTE.first(),
    val item: FilterField = FilterField.ADDRESS,
    val words: List<String> = emptyList(),
    val scope: AddressScope = AddressScope.ANY,
    val min: Double? = null,
    val max: Double? = null,
    val enabled: Boolean = true,
    val paint: Boolean = true,
    val speak: Boolean = false,
) {
    /** Tem o que comparar: palavras (endereço) ou pelo menos um dos limites, com de ≤ até (número). */
    val isComplete: Boolean
        get() = if (item.isText) {
            words.isNotEmpty()
        } else {
            (min != null || max != null) && (min == null || max == null || min <= max)
        }

    /** Nome na etiqueta e na voz: o que o usuário deu ou, sem nome, o resumo curto da regra. */
    val displayName: String
        get() = name.trim().ifEmpty { if (item.isText) words.joinToString(", ") else CardFilters.summary(this) }
}

/** Regras dos filtros do Card: o que casa, em que ordem, textos e gravação. */
object CardFilters {

    /** Limite de filtros (a lista é conferida a cada leitura da oferta). */
    const val MAX_FILTERS = 20
    const val MAX_WORDS = 30
    private const val MAX_WORD_LENGTH = 40
    private const val MAX_NAME_LENGTH = 30

    /** Cores para escolher (ARGB): a 1ª é a do filtro novo; filtro novo pega a próxima sem uso. */
    val PALETTE: List<Int> = listOf(
        0xFF1565C0, // azul
        0xFFAD1457, // magenta
        0xFFFF6D00, // laranja
        0xFF00BFA5, // verde-água
        0xFF7B1FA2, // roxo
        0xFF212121, // preto
        0xFF5D4037, // marrom
        0xFF546E7A, // cinza azulado
        0xFFFFD600, // amarelo vivo
        0xFFFFFFFF, // branco
        0xFFC62828, // vermelho
        0xFF2E7D32, // verde
    ).map { it.toInt() }

    /** Filtros ligados e completos que casam com a oferta, na ordem da lista (o de cima vale mais). */
    fun matching(filters: List<CardFilter>, o: EvaluatedOffer): List<CardFilter> =
        filters.filter { it.enabled && matches(it, o) }

    fun matches(f: CardFilter, o: EvaluatedOffer): Boolean {
        if (!f.isComplete) return false
        if (f.item.isText) {
            val addresses = addresses(f.scope, o)
            return f.words.any { w -> addresses.any { containsWord(it, w) } }
        }
        val raw = value(f.item, o) ?: return false
        // Compara o número como o Card mostra (R$ 1,996/km aparece "2,00" e casa com "a partir de 2").
        val v = round(raw, f.item.decimals)
        return (f.min == null || v >= f.min) && (f.max == null || v <= f.max)
    }

    /** Cor do fundo do Card: a do 1º filtro que casou e pinta; null = fica o semáforo. */
    fun paintColor(matched: List<CardFilter>): Int? = matched.firstOrNull { it.paint }?.color

    /** Nomes que a voz diz (filtros com "falar o nome"), sem repetir. */
    fun spoken(matched: List<CardFilter>): List<String> =
        matched.filter { it.speak }.map { it.displayName }.filter { it.isNotBlank() }.distinct()

    /** Número da oferta para [field] (o mesmo do Card; trecho não lido vem estimado); null = não lido. */
    fun value(field: FilterField, o: EvaluatedOffer): Double? {
        val m = o.metrics
        return when (field) {
            FilterField.ADDRESS -> null
            FilterField.PRICE -> o.raw.price
            FilterField.PER_KM -> m.perKm
            FilterField.PER_HOUR -> m.perHour
            FilterField.PER_MIN -> CardValues.perMin(o)
            FilterField.NET_PROFIT -> m.netProfit
            FilterField.NET_PER_HOUR -> CardValues.netPerHour(o)
            FilterField.NET_PERCENT -> CardValues.netPercent(o)
            FilterField.RATING -> o.raw.passengerRating
            FilterField.PICKUP_KM -> OfferEvaluator.pickupLeg(o.raw).km
            FilterField.PICKUP_MIN -> OfferEvaluator.pickupLeg(o.raw).minutes
            FilterField.TRIP_KM -> OfferEvaluator.tripLeg(o.raw).km
            FilterField.TRIP_MIN -> OfferEvaluator.tripLeg(o.raw).minutes
            FilterField.TOTAL_KM -> m.totalKm
            FilterField.TOTAL_MIN -> m.totalMin?.toDouble()
            FilterField.STOPS -> RideRoute.stopCount(o.raw).toDouble()
        }
    }

    private fun addresses(scope: AddressScope, o: EvaluatedOffer): List<String> = when (scope) {
        AddressScope.ANY -> listOfNotNull(o.raw.pickupAddress) + o.raw.stopAddresses + listOfNotNull(o.raw.dropoffAddress)
        AddressScope.PICKUP -> listOfNotNull(o.raw.pickupAddress)
        AddressScope.DROPOFF -> listOfNotNull(o.raw.dropoffAddress)
    }

    /**
     * [word] (uma palavra ou várias seguidas, "vila nova") aparece em [address] como palavra inteira,
     * sem diferenciar maiúsculas, acentos nem pontuação: "se" casa com "Praça da Sé", não com "José".
     */
    fun containsWord(address: String?, word: String): Boolean {
        val needle = tokens(word)
        if (address == null || needle.isEmpty()) return false
        val hay = tokens(address)
        return (0..hay.size - needle.size).any { i -> needle.indices.all { j -> hay[i + j] == needle[j] } }
    }

    private fun tokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .split(NOT_WORD)
            .filter { it.isNotEmpty() }

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^a-z0-9]+")
    private val SEPARATORS = Regex("[,;\\n\\r\\t|]+")

    /**
     * Palavras digitadas ("centro, Vila Nova; aeroporto") → lista limpa: separadas por vírgula, ponto
     * e vírgula ou linha, sem vazias nem repetidas (acento e maiúscula não contam como diferença).
     */
    fun parseWords(text: String): List<String> {
        val seen = HashSet<List<String>>()
        return text.split(SEPARATORS)
            .map { it.trim().replace(Regex("\\s+"), " ").take(MAX_WORD_LENGTH).trim() }
            .filter { w -> tokens(w).let { it.isNotEmpty() && seen.add(it) } }
            .take(MAX_WORDS)
    }

    /** Regra em uma linha, para a lista de filtros: "R$/Km a partir de 2,00", "Endereço · só destino: aeroporto". */
    fun summary(f: CardFilter): String {
        val field = f.item
        if (field.isText) {
            val where = if (f.scope == AddressScope.ANY) "" else " · ${f.scope.label.lowercase(Locale.ROOT)}"
            return "${field.label}$where: " + (f.words.joinToString(", ").ifEmpty { "sem palavras" })
        }
        fun n(v: Double) = CardValues.num(v, field.decimals)
        return when {
            f.min != null && f.max != null -> "${field.label} de ${n(f.min)} até ${n(f.max)}"
            f.min != null -> "${field.label} a partir de ${n(f.min)}"
            f.max != null -> "${field.label} até ${n(f.max)}"
            else -> "${field.label}: sem valor"
        }
    }

    /** Texto escuro sobre [color] (cor clara)? Senão, branco. */
    fun darkText(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.6
    }

    /** Filtro novo: id único, a próxima cor da paleta sem uso e a voz ligada (o padrão é endereço). */
    fun create(filters: List<CardFilter>, now: Long): CardFilter {
        val id = maxOf(now, (filters.maxOfOrNull { it.id } ?: 0L) + 1)
        val used = filters.map { it.color }.toSet()
        val color = PALETTE.firstOrNull { it !in used } ?: PALETTE[filters.size % PALETTE.size]
        return CardFilter(id = id, color = color, speak = true)
    }

    /** Salva [f]: troca o de mesmo id ou entra no fim (até [MAX_FILTERS]). Nome e palavras ficam limpos. */
    fun upsert(filters: List<CardFilter>, f: CardFilter): List<CardFilter> {
        val clean = f.copy(
            name = cleanName(f.name),
            words = if (f.item.isText) parseWords(f.words.joinToString(",")) else emptyList(),
            min = if (f.item.isText) null else f.min?.takeIf { it.isFinite() },
            max = if (f.item.isText) null else f.max?.takeIf { it.isFinite() },
        )
        return if (filters.any { it.id == f.id }) {
            filters.map { if (it.id == f.id) clean else it }
        } else {
            (filters + clean).take(MAX_FILTERS)
        }
    }

    fun remove(filters: List<CardFilter>, id: Long): List<CardFilter> = filters.filterNot { it.id == id }

    /** Sobe ([delta] = -1) ou desce (+1) o filtro na lista: o de cima vale mais na cor do Card. */
    fun move(filters: List<CardFilter>, id: Long, delta: Int): List<CardFilter> {
        val from = filters.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in filters.indices) return filters
        val list = filters.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    private fun cleanName(name: String): String =
        name.replace(Regex("[\\t\\n\\r]+"), " ").trim().take(MAX_NAME_LENGTH).trim()

    private fun round(v: Double, decimals: Int): Double {
        val p = 10.0.pow(decimals)
        return Math.round(v * p) / p
    }

    // ------------------------------------------------------------------ gravação (uma linha por filtro)

    private const val SEP = "\t"
    private const val VERSION = "v1"

    fun encode(filters: List<CardFilter>): String = filters.joinToString("\n") { f ->
        listOf(
            VERSION,
            f.id.toString(),
            flag(f.enabled),
            flag(f.paint),
            flag(f.speak),
            String.format(Locale.ROOT, "%08X", f.color),
            f.item.id,
            f.scope.id,
            f.min?.toString().orEmpty(),
            f.max?.toString().orEmpty(),
            cleanName(f.name),
            parseWords(f.words.joinToString(",")).joinToString(","),
        ).joinToString(SEP)
    }

    /** Linhas inválidas são ignoradas (não derrubam as outras); id repetido fica só o primeiro. */
    fun decode(text: String?): List<CardFilter> {
        if (text.isNullOrBlank()) return emptyList()
        val ids = HashSet<Long>()
        return text.split('\n').mapNotNull { row ->
            val c = row.split(SEP)
            if (c.size < 12 || c[0] != VERSION) return@mapNotNull null
            val id = c[1].toLongOrNull() ?: return@mapNotNull null
            val color = c[5].toLongOrNull(16)?.toInt() ?: return@mapNotNull null
            val field = FilterField.fromId(c[6]) ?: return@mapNotNull null
            if (!ids.add(id)) return@mapNotNull null
            CardFilter(
                id = id,
                name = cleanName(c[10]),
                color = color,
                item = field,
                words = parseWords(c[11]),
                scope = AddressScope.fromId(c[7]),
                min = c[8].toDoubleOrNull()?.takeIf { it.isFinite() },
                max = c[9].toDoubleOrNull()?.takeIf { it.isFinite() },
                enabled = c[2] == "1",
                paint = c[3] == "1",
                speak = c[4] == "1",
            )
        }.take(MAX_FILTERS)
    }

    private fun flag(b: Boolean) = if (b) "1" else "0"
}
