package br.com.mapp.reader.parser

import br.com.mapp.core.model.TripEvent
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.shift.TripMatcher

/**
 * Reconhece as telas DEPOIS da oferta: corrida aceita (indo buscar), passageiro a bordo e corrida
 * finalizada — com o primeiro nome do passageiro quando a tela mostra. Função pura, testável na JVM.
 * Compara sem acento e sem maiúsculas; o OCR às vezes cola as palavras ("Comofoiaviagem?",
 * "EncontrocomCaio").
 */
object TripScreens {
    // Uber (prints de 12 e 13/11/2026). Os demais são palpites para a 99/inDrive — CALIBRAR com prints.
    private val finished = listOf(
        "comofoiaviagem", "avaliarusuario", "avaliarpassageiro", "comofoiacorrida", "avalieopassageiro",
    )
    private val accepted = listOf(
        "encontrocom", "acaminhodopassageiro", "buscarpassageiro", "iniciarviagem", "iniciarcorrida",
    )
    /** Tela inicial sem corrida (Uber: "Procurando viagens"). */
    private val idle = listOf("procurandoviagens", "voceestaoffline")

    /** "Encontro com Caio Augusto" → caio (indo buscar). */
    private val pickupName = Regex("""encontro\s*com\b\s*([a-z]*)""")
    /** "Destino de Ana" → ana (passageiro a bordo). */
    private val dropoffName = Regex("""destino\s*de\b\s*([a-z]*)""")
    private val onlyLetters = Regex("""^[a-z]+""")

    /** Palavras que podem vir coladas depois do nome e não são nome. */
    private val notNames = setOf("de", "da", "do", "com", "min", "km")

    /**
     * [fromOcr] = texto da imagem (o que está visível). A tela inicial só vale na imagem: o texto da
     * Acessibilidade mistura a tela de fundo e pode ter "Procurando viagens" durante a corrida.
     */
    fun detect(lines: List<String>, fromOcr: Boolean = false): TripEvent? {
        if (lines.isEmpty()) return null
        val folded = lines.map { ParseUtils.fold(it) }
        val keys = folded.map { line -> line.filter { it.isLetterOrDigit() } }
        if (keys.any { k -> finished.any { k.contains(it) } }) return TripEvent(TripStage.FINISHED)
        val km = screenKm(lines)
        nameAfter(folded, pickupName)?.let { return TripEvent(TripStage.ACCEPTED, it.ifEmpty { null }, km) }
        if (keys.any { k -> accepted.any { k.contains(it) } }) return TripEvent(TripStage.ACCEPTED, km = km)
        nameAfter(folded, dropoffName)?.let { name -> if (name.isNotEmpty()) return TripEvent(TripStage.ON_TRIP, name, km) }
        if (fromOcr && keys.any { k -> idle.any { k.contains(it) } }) return TripEvent(TripStage.IDLE)
        return null
    }

    /** Distância até a busca/destino que a tela mostra ("2 min · 0,9 km"); ignora R$/km. */
    private fun screenKm(lines: List<String>): Double? = lines.asSequence()
        .filter { '$' !in it && "/km" !in it.lowercase() }
        .mapNotNull { ParseUtils.parseDistanceKm(it) }
        .firstOrNull()

    /**
     * Nome depois da frase: na mesma linha ou, se a linha acaba na frase, na seguinte.
     * null = frase não está na tela; "" = frase está, mas o nome não foi lido.
     */
    private fun nameAfter(folded: List<String>, phrase: Regex): String? {
        folded.forEachIndexed { i, line ->
            // "de"/"com" precisam terminar a palavra ("destino definido" não é "Destino de <nome>").
            val name = phrase.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (name.length >= 2 && name !in notNames) return name
            val next = folded.getOrNull(i + 1)?.trim()?.let { onlyLetters.find(it)?.value }
            return if (next != null && next.length >= 2 && next !in notNames) next else ""
        }
        return null
    }
}

/**
 * Transforma leituras da tela (texto e imagem, várias por segundo) em eventos únicos: avisa só
 * quando a etapa ou o passageiro muda. A etapa só é "esquecida" depois de [holdMs] sem aparecer;
 * a mesma etapa vista de novo depois disso é avisada de novo.
 *
 * Confirmação: em geral [confirm] leituras iguais seguidas (um quadro mal lido — "Ana" lida como
 * "Aria" — não vira corrida nova). Mas telas que passam rápido não podem se perder:
 * - "Como foi a viagem?" (frase fixa, sem nome) vale com uma leitura;
 * - troca de etapa lida uma vez e que some da tela antes da 2ª leitura (aceite ou início rápido,
 *   Waze por cima) vale assim mesmo. Nome diferente na MESMA etapa continua precisando de 2 leituras.
 * - tela inicial ([TripStage.IDLE]) precisa de 2 leituras e, vista sem parar, é avisada de novo a
 *   cada [idleRepeatMs] (fecha corrida que só pôde ser decidida depois).
 */
class TripStageTracker(
    private val holdMs: Long = 20_000L,
    private val confirm: Int = 2,
    private val idleRepeatMs: Long = 30_000L,
) {
    private var current: TripEvent? = null
    private var lastSeenAt = 0L
    private var publishedAt = 0L
    private var pending: TripEvent? = null
    private var pendingHits = 0
    private var pendingAt = 0L

    /** Há etapa lida uma vez esperando confirmação (vale ler a tela de novo logo). */
    val hasPending: Boolean get() = pending != null

    /** Último evento novo desta leitura (null = nada mudou). */
    fun onScreen(event: TripEvent?, now: Long): TripEvent? = read(event, now).lastOrNull()

    /**
     * [event] = o que esta leitura viu (null = nenhuma das telas, ou o app de corrida nem está na
     * frente). Devolve os eventos novos, em ordem.
     */
    fun read(event: TripEvent?, now: Long): List<TripEvent> {
        val out = ArrayList<TripEvent>(2)
        val p = pending
        if (p != null && (event == null || !same(event, p))) {
            // A pendente não se repetiu: se era troca de etapa, a tela passou rápido — vale assim.
            pending = null
            if (now - pendingAt <= holdMs && flushable(p, active(now))) emit(p, now, out)
        }
        if (event == null) return out
        val cur = active(now)
        if (cur != null && matches(event, cur)) {
            lastSeenAt = now
            if (cur.stage == TripStage.IDLE && now - publishedAt >= idleRepeatMs) {
                publishedAt = now
                out += cur
            }
            return out
        }
        val q = pending
        if (q != null && same(event, q)) {
            pendingHits++
        } else {
            pending = event
            pendingHits = 1
        }
        pendingAt = now
        val needed = if (event.stage == TripStage.FINISHED) 1 else confirm
        if (pendingHits >= needed) {
            val confirmed = pending!!
            pending = null
            emit(confirmed, now, out)
        }
        return out
    }

    fun reset() {
        current = null
        pending = null
    }

    private fun active(now: Long): TripEvent? = current?.takeIf { now - lastSeenAt <= holdMs }

    private fun emit(e: TripEvent, now: Long, out: MutableList<TripEvent>) {
        current = e
        lastSeenAt = now
        publishedAt = now
        out += e
    }

    /** Lida uma vez só pode valer: troca de etapa (não nome diferente na mesma etapa), fora a tela inicial. */
    private fun flushable(p: TripEvent, cur: TripEvent?): Boolean =
        p.stage != TripStage.IDLE && (cur == null || cur.stage != p.stage)

    private fun same(a: TripEvent, b: TripEvent): Boolean = matches(a, b) && matches(b, a)

    /** [read] repete [known]? Leitura sem nome casa com qualquer nome da mesma etapa. */
    private fun matches(read: TripEvent, known: TripEvent): Boolean =
        read.stage == known.stage && (
            read.passenger == null ||
                (known.passenger != null && TripMatcher.sameName(read.passenger, known.passenger))
            )
}
