package br.com.mapp.core.trip

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.repo.OfferRecord
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Viagem inserida à mão no Histórico de ofertas: lida na tela **"Detalhes da viagem"** da Uber
 * ([TripDetailsScreen]) ou digitada. Campos não lidos = null.
 */
data class TripDetails(
    val platform: Platform = Platform.UBER,
    val price: Double? = null,
    /** Tipo como a Uber mostra ("Electric", "UberX", "Comfort"…). */
    val category: String? = null,
    /** Data e hora da viagem (a do cabeçalho da Uber). */
    val atMillis: Long? = null,
    val km: Double? = null,
    /** Duração em segundos ("4 min 49 segundos" = 289). */
    val seconds: Int? = null,
    val pickup: String? = null,
    val dropoff: String? = null,
    val stops: List<String> = emptyList(),
) {
    /** Duração arredondada em minutos (mínimo 1). */
    val minutes: Int? get() = seconds?.let { ((it / 60.0).roundToInt()).coerceAtLeast(1) }

    /** Tudo que a tela da Uber mostra foi lido. */
    val complete: Boolean
        get() = price != null && atMillis != null && km != null && seconds != null && pickup != null && dropoff != null

    /** Quantos campos vieram (para guardar a melhor leitura). */
    val score: Int
        get() = listOf(price, category, atMillis, km, seconds, pickup, dropoff).count { it != null }
}

/**
 * Lê a tela **"Detalhes da viagem"** do app do motorista da Uber (Ganhos → atividade → uma viagem):
 * cabeçalho "Electric • 27 de nov. de 2026 • 6:35", valor "R$ 6,47", "Duração 4 min 49 segundos",
 * "Distância 1.95 km" e os endereços de coleta e destino. Funciona com o texto da Acessibilidade e com
 * as linhas do OCR (endereço quebrado em duas linhas é juntado). Kotlin puro, sem Android.
 */
object TripDetailsScreen {

    private val TITLES = listOf("detalhes da viagem", "trip details")
    private val LABELS = listOf("duracao", "distancia", "duration", "distance")

    /** Onde acaba a lista de endereços (seção de ganhos/pontos embaixo). */
    private val STOP_WORDS = listOf(
        "pontos ganhos", "seus ganhos", "your earnings", "points earned", "ganhos", "tarifa", "ajuda", "help",
    )

    private val MONEY = Regex("""^r\$ ?(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})$""")
    private val MONEY_ANY = Regex("""r\$ ?(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})""")
    private val KM = Regex("""(\d{1,4}(?:[.,]\d{1,2})?) ?km\b""")
    private val DURATION_TOKEN =
        Regex("""(\d{1,3}) ?(horas?|hrs?|h|minutos?|mins?|min|segundos?|seg|s|seconds?|secs?)\b""")
    private val DURATION_HINT = Regex("""\b(min|minutos?|mins?|seg|segundos?|horas?|h|seconds?|secs?)\b""")
    private val DATE_PT = Regex("""(\d{1,2}) de ([a-z]{3,9})\.? de (\d{4})""")
    private val DATE_EN = Regex("""\b([a-z]{3,9})\.? (\d{1,2}),? (\d{4})""")
    private val TIME = Regex("""\b(\d{1,2}):(\d{2})(?: ?([ap])\.? ?m\.?)?""")
    private val CEP = Regex("""\b\d{5}-?\d{3}\b""")
    private val STREET =
        Regex("""^(?:(?:r|av|al|tv|estr|rod|pc)\.|(?:rua|avenida|av|alameda|travessa|estrada|rodovia|praca|largo|beco)\b)""")
    private val ONLY_NUMBERS = Regex("""^[\d\s.,:%+-]+$""")
    private val SEPARATORS = Regex("""\s*[•·|]\s*""")

    private val MONTHS = mapOf(
        "jan" to 1, "fev" to 2, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4, "mai" to 5, "may" to 5,
        "jun" to 6, "jul" to 7, "ago" to 8, "aug" to 8, "set" to 9, "sep" to 9, "out" to 10, "oct" to 10,
        "nov" to 11, "dez" to 12, "dec" to 12,
    )

    /**
     * null = não é a tela "Detalhes da viagem" ou o valor não foi lido. [fromOcr] = linhas da imagem
     * (endereço pode vir quebrado em duas); do texto da Acessibilidade, cada texto é um endereço inteiro.
     */
    fun read(
        platform: Platform,
        texts: List<String>,
        fromOcr: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TripDetails? {
        if (platform != Platform.UBER || texts.isEmpty()) return null
        val norm = texts.map(::normalize)
        val title = norm.indexOfFirst { t -> TITLES.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") } }
        if (title < 0) return null
        val body = title + 1

        val price = priceOf(norm, body) ?: return null

        var at: Long? = null
        var category: String? = null
        for (i in body until norm.size) {
            val date = dateOf(norm[i], zone) ?: continue
            at = date
            category = categoryOf(texts[i])
            break
        }

        var km: Double? = null
        var kmAt = -1
        var seconds: Int? = null
        var secondsAt = -1
        for (i in body until norm.size) {
            val t = norm[i]
            if (MONEY_ANY.containsMatchIn(t) || isStreet(t)) continue
            if (km == null) KM.find(t)?.let { m ->
                km = m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..2_000.0 }
                if (km != null) kmAt = i
            }
            if (seconds == null && DURATION_HINT.containsMatchIn(t.replace(KM, " "))) {
                seconds = durationOf(t.replace(KM, " "))
                if (seconds != null) secondsAt = i
            }
        }

        val addressFrom = maxOf(kmAt, secondsAt).takeIf { it >= 0 }?.plus(1) ?: body
        val addresses = addressesOf(texts, norm, addressFrom, fromOcr)
        return TripDetails(
            platform = platform,
            price = price,
            category = category,
            atMillis = at,
            km = km,
            seconds = seconds,
            pickup = addresses.firstOrNull(),
            dropoff = addresses.drop(1).lastOrNull(),
            stops = if (addresses.size > 2) addresses.subList(1, addresses.size - 1) else emptyList(),
        )
    }

    /** O 1º valor sozinho na linha (o grande do topo); sem ele, o "Preço antecipado: R$ X". */
    private fun priceOf(norm: List<String>, from: Int): Double? {
        for (i in from until norm.size) MONEY.matchEntire(norm[i])?.let { return money(it) }
        for (i in from until norm.size) {
            if ("preco" in norm[i] || "price" in norm[i]) MONEY_ANY.find(norm[i])?.let { return money(it) }
        }
        return null
    }

    private fun money(m: MatchResult): Double? {
        val reais = m.groupValues[1].replace(".", "").toDoubleOrNull() ?: return null
        val cents = m.groupValues[2].toDoubleOrNull() ?: return null
        return (reais + cents / 100.0).takeIf { it in 0.01..20_000.0 }
    }

    private fun dateOf(t: String, zone: ZoneId): Long? {
        var day: Int
        var month: Int
        var year: Int
        val pt = DATE_PT.find(t)
        if (pt != null) {
            day = pt.groupValues[1].toInt()
            month = MONTHS[pt.groupValues[2].take(3)] ?: return null
            year = pt.groupValues[3].toInt()
        } else {
            val en = DATE_EN.find(t) ?: return null
            month = MONTHS[en.groupValues[1].take(3)] ?: return null
            day = en.groupValues[2].toInt()
            year = en.groupValues[3].toInt()
        }
        var hour = 0
        var minute = 0
        TIME.find(t)?.let { m ->
            hour = m.groupValues[1].toInt()
            minute = m.groupValues[2].toInt()
            when (m.groupValues[3]) {
                "p" -> if (hour < 12) hour += 12
                "a" -> if (hour == 12) hour = 0
            }
        }
        return try {
            LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /** "Electric • 27 de nov…" → "Electric" (o que vem antes da data, se for um nome curto). */
    private fun categoryOf(original: String): String? {
        val first = SEPARATORS.split(original.trim()).firstOrNull()?.trim().orEmpty()
        val name = if (first.any { it.isDigit() }) {
            // OCR leu o "•" como outra coisa: pega o texto antes do dia.
            Regex("""^(.*?)[\s.\-–]+\d{1,2} de """).find(first)?.groupValues?.get(1)?.trim().orEmpty()
        } else {
            first
        }
        return name.takeIf { it.length in 2..24 && it.any(Char::isLetter) && !it.any(Char::isDigit) }
    }

    private fun durationOf(t: String): Int? {
        var total = 0
        var found = false
        for (m in DURATION_TOKEN.findAll(t)) {
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            found = true
        }
        return total.takeIf { found && it in 1..(24 * 3600) }
    }

    /**
     * Endereços depois da duração/distância, até a seção de ganhos. Nomes soltos do mapa (sem vírgula e
     * sem "Rua/Av.") ficam fora. No OCR a linha quebrada é juntada até o CEP ou o "Brasil" (ou até a
     * próxima linha que começa com "Rua/Av.").
     */
    private fun addressesOf(texts: List<String>, norm: List<String>, from: Int, fromOcr: Boolean): List<String> {
        val out = ArrayList<String>()
        val buffer = ArrayList<String>()
        fun flush() {
            if (buffer.isNotEmpty()) out += buffer.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            buffer.clear()
        }
        for (i in from until norm.size) {
            val t = norm[i]
            if (STOP_WORDS.any { t.startsWith(it) }) break
            if (t.length < 4 || ONLY_NUMBERS.matches(t) || MONEY_ANY.containsMatchIn(t)) continue
            if (LABELS.any { t.startsWith(it) }) continue
            if (!fromOcr) {
                if (isAddressLike(t)) out += texts[i].trim()
                continue
            }
            if (buffer.isNotEmpty() && STREET.containsMatchIn(t)) flush()
            if (buffer.isEmpty() && !isAddressLike(t)) continue
            buffer += texts[i].trim()
            if (CEP.containsMatchIn(t) || t.endsWith("brasil") || t.endsWith("brazil")) flush()
        }
        flush()
        return out
    }

    /** Começa com "Rua/Av." ou tem CEP/"Brasil": é endereço com certeza (não é a linha da distância). */
    private fun isStreet(t: String): Boolean =
        STREET.containsMatchIn(t) || CEP.containsMatchIn(t) || t.endsWith("brasil") || t.endsWith("brazil")

    private fun isAddressLike(t: String): Boolean =
        isStreet(t) || (',' in t && t.any(Char::isLetter) && t.length >= 8)

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * **"Puxar da Uber"** (Histórico de ofertas → Inserir viagem): o MApp abre a Uber e fica esperando a
 * tela "Detalhes da viagem" por [ARM_MS]. O leitor oferece cada leitura em [offer]; leitura completa vale
 * na hora, incompleta vale depois de [PARTIAL_WAIT_MS] com a melhor que apareceu. O resultado fica em
 * [result] até a tela de Ofertas pegar ([consume]). Só lê — nunca toca na tela da Uber.
 */
object TripCapture {
    const val ARM_MS = 5 * 60_000L
    const val PARTIAL_WAIT_MS = 2_500L

    private val _armedUntil = MutableStateFlow(0L)
    /** Até quando (relógio do aparelho) o leitor procura a tela; 0 = não está procurando. */
    val armedUntil: StateFlow<Long> = _armedUntil.asStateFlow()

    private val _result = MutableStateFlow<TripDetails?>(null)
    /** Viagem lida, esperando a tela de Ofertas abrir o formulário. */
    val result: StateFlow<TripDetails?> = _result.asStateFlow()

    private var best: TripDetails? = null
    private var firstSeenAt = 0L

    @Synchronized
    fun arm(now: Long) {
        best = null
        firstSeenAt = 0L
        _result.value = null
        _armedUntil.value = now + ARM_MS
    }

    @Synchronized
    fun cancel() {
        best = null
        firstSeenAt = 0L
        _armedUntil.value = 0L
    }

    fun isArmed(now: Long): Boolean = now < _armedUntil.value

    /** Leitura da tela. true = a viagem foi entregue agora (o leitor traz o MApp de volta). */
    @Synchronized
    fun offer(read: TripDetails, now: Long): Boolean {
        if (!isArmed(now) || read.price == null) return false
        val prior = best
        if (prior == null || prior.price != read.price || prior.atMillis != read.atMillis) {
            // Outra viagem na tela: começa a contar de novo.
            best = read
            firstSeenAt = now
        } else if (read.score >= prior.score) {
            best = read
        }
        val chosen = best ?: read
        if (!read.complete && now - firstSeenAt < PARTIAL_WAIT_MS) return false
        best = null
        firstSeenAt = 0L
        _armedUntil.value = 0L
        _result.value = if (read.complete) read else chosen
        return true
    }

    /** A tela de Ofertas pegou a viagem para o formulário. */
    @Synchronized
    fun consume(): TripDetails? = _result.value.also { _result.value = null }
}

/** Contas da viagem inserida à mão. */
object ManualTrip {
    /** Marca no texto bruto da oferta (o histórico mostra de onde ela veio). */
    const val TAG = "[inserida à mão]"

    /** Oferta da mesma viagem: mesmo app e valor, até esta distância no tempo. */
    const val MATCH_WINDOW_MS = 30 * 60_000L

    private val INPUT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withResolverStyle(ResolverStyle.STRICT)

    /** A oferta que vai para o histórico (aceita) e para a jornada. [atMillis] e [price] obrigatórios. */
    fun offer(d: TripDetails, atMillis: Long): RawOffer = RawOffer(
        platform = d.platform,
        price = d.price ?: 0.0,
        tripKm = d.km,
        tripMin = d.minutes,
        pickupAddress = d.pickup?.takeIf { it.isNotBlank() },
        dropoffAddress = d.dropoff?.takeIf { it.isNotBlank() },
        category = d.category?.trim()?.takeIf { it.isNotEmpty() },
        rawText = TAG,
        capturedAt = atMillis,
        stopAddresses = d.stops,
        stopCount = d.stops.size,
    )

    /**
     * Oferta já gravada que é esta viagem: mesmo app, mesmo valor (±1 centavo) e até [MATCH_WINDOW_MS]
     * do horário — a mais perto no tempo. null = viagem nova.
     */
    fun match(d: TripDetails, offers: List<OfferRecord>): OfferRecord? {
        val price = d.price ?: return null
        val at = d.atMillis ?: return null
        return offers
            .filter { r ->
                val raw = r.evaluated.raw
                raw.platform == d.platform && abs(raw.price - price) < 0.011 && abs(raw.capturedAt - at) <= MATCH_WINDOW_MS
            }
            .minByOrNull { abs(it.evaluated.raw.capturedAt - at) }
    }

    /** "27/11/2026 06:35" → horário; inválido = null. */
    fun parseDateTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(text.trim(), INPUT).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun formatDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        INPUT.format(Instant.ofEpochMilli(millis).atZone(zone))
}
