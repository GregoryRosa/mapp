package br.com.mapp.reader.parser

import br.com.mapp.core.model.TripStage

/**
 * Reconhece as telas DEPOIS da oferta: corrida aceita (indo buscar) e corrida finalizada.
 * Função pura, testável na JVM. Compara sem acento, sem maiúsculas e sem espaços (o OCR às vezes
 * cola as palavras: "Comofoiaviagem?").
 */
object TripScreens {
    // Uber (prints de 12/11/2026). Os demais são palpites para a 99/inDrive — CALIBRAR com prints.
    private val finished = listOf(
        "comofoiaviagem", "avaliarusuario", "avaliarpassageiro", "comofoiacorrida", "avalieopassageiro",
    )
    private val accepted = listOf(
        "encontrocom", "acaminhodopassageiro", "buscarpassageiro", "iniciarviagem", "iniciarcorrida",
    )

    fun detect(lines: List<String>): TripStage? {
        if (lines.isEmpty()) return null
        val keys = lines.map { line -> ParseUtils.fold(line).filter { it.isLetterOrDigit() } }
        return when {
            keys.any { k -> finished.any { k.contains(it) } } -> TripStage.FINISHED
            keys.any { k -> accepted.any { k.contains(it) } } -> TripStage.ACCEPTED
            else -> null
        }
    }
}

/**
 * Transforma leituras da tela (texto e imagem, várias por segundo) em eventos únicos: avisa só
 * quando a etapa muda. Um quadro mal lido no meio não gera aviso repetido: a etapa só é
 * "esquecida" depois de [holdMs] sem aparecer. Isso vale também sem leituras no meio (Waze na
 * frente, tela apagada): a mesma etapa vista de novo depois de [holdMs] é uma corrida nova.
 */
class TripStageTracker(private val holdMs: Long = 20_000L) {
    private var current: TripStage? = null
    private var lastSeenAt = 0L

    /** [stage] = o que esta leitura viu (null = nenhuma das telas). Devolve a etapa nova, se mudou. */
    fun onScreen(stage: TripStage?, now: Long): TripStage? {
        if (stage == null) {
            if (current != null && now - lastSeenAt > holdMs) current = null
            return null
        }
        val gone = now - lastSeenAt > holdMs
        lastSeenAt = now
        if (stage == current && !gone) return null
        current = stage
        return stage
    }

    fun reset() {
        current = null
    }
}
