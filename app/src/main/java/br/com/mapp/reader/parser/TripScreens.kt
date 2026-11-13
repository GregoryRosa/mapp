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

    /** "Encontro com Caio Augusto" → caio (indo buscar). */
    private val pickupName = Regex("""encontro\s*com\b\s*([a-z]*)""")
    /** "Destino de Ana" → ana (passageiro a bordo). */
    private val dropoffName = Regex("""destino\s*de\b\s*([a-z]*)""")
    private val onlyLetters = Regex("""^[a-z]+""")

    /** Palavras que podem vir coladas depois do nome e não são nome. */
    private val notNames = setOf("de", "da", "do", "com", "min", "km")

    fun detect(lines: List<String>): TripEvent? {
        if (lines.isEmpty()) return null
        val folded = lines.map { ParseUtils.fold(it) }
        val keys = folded.map { line -> line.filter { it.isLetterOrDigit() } }
        if (keys.any { k -> finished.any { k.contains(it) } }) return TripEvent(TripStage.FINISHED)
        nameAfter(folded, pickupName)?.let { return TripEvent(TripStage.ACCEPTED, it.ifEmpty { null }) }
        if (keys.any { k -> accepted.any { k.contains(it) } }) return TripEvent(TripStage.ACCEPTED)
        nameAfter(folded, dropoffName)?.let { name -> if (name.isNotEmpty()) return TripEvent(TripStage.ON_TRIP, name) }
        return null
    }

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
 * quando a etapa ou o passageiro muda, e só depois de [confirm] leituras iguais seguidas (um quadro
 * mal lido — "Ana" lida como "Aria" — não vira corrida nova). A etapa só é "esquecida" depois de
 * [holdMs] sem aparecer; a mesma etapa vista de novo depois disso é avisada de novo.
 */
class TripStageTracker(private val holdMs: Long = 20_000L, private val confirm: Int = 2) {
    private var current: TripEvent? = null
    private var lastSeenAt = 0L
    private var pending: TripEvent? = null
    private var pendingHits = 0
    private var pendingAt = 0L

    /** [event] = o que esta leitura viu (null = nenhuma das telas). Devolve o evento novo, se mudou. */
    fun onScreen(event: TripEvent?, now: Long): TripEvent? {
        if (event == null) {
            if (current != null && now - lastSeenAt > holdMs) current = null
            return null
        }
        val cur = current
        if (cur != null && now - lastSeenAt <= holdMs && matches(event, cur)) {
            lastSeenAt = now
            pending = null
            return null
        }
        val p = pending
        if (p != null && now - pendingAt <= holdMs && matches(event, p) && matches(p, event)) {
            pendingHits++
        } else {
            pending = event
            pendingHits = 1
        }
        pendingAt = now
        if (pendingHits < confirm) return null
        val confirmed = pending!!
        current = confirmed
        lastSeenAt = now
        pending = null
        return confirmed
    }

    fun reset() {
        current = null
        pending = null
    }

    /** [read] repete [known]? Leitura sem nome casa com qualquer nome da mesma etapa. */
    private fun matches(read: TripEvent, known: TripEvent): Boolean =
        read.stage == known.stage && (
            read.passenger == null ||
                (known.passenger != null && TripMatcher.sameName(read.passenger, known.passenger))
            )
}
