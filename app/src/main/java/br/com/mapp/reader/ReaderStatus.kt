package br.com.mapp.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Diagnóstico do leitor (só em memória, nunca sai do aparelho): mostra na tela inicial e no ícone
 * flutuante se a Acessibilidade está conectada, quando leu a Uber/99/inDrive pela última vez e
 * o texto lido — para o motorista copiar e calibrar o parser.
 */
object ReaderStatus {

    data class Snapshot(
        /** Serviço de Acessibilidade ligado e conectado pelo sistema. */
        val connected: Boolean = false,
        /** Último evento de um app de corrida (System.currentTimeMillis), 0 = nunca. */
        val lastEventAt: Long = 0L,
        val lastPlatform: String? = null,
        /** Quantos textos foram lidos na última leitura. */
        val lastTextCount: Int = 0,
        /** Última leitura que tinha algum "R$" (provável oferta), para copiar. */
        val lastMoneyTexts: List<String> = emptyList(),
        val lastMoneyAt: Long = 0L,
        /** Resultado do parser para essa leitura. */
        val lastMoneyRecognized: Boolean = false,
        /** Última oferta reconhecida (resumo legível). */
        val lastOffer: String? = null,
        val lastOfferAt: Long = 0L,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setConnected(connected: Boolean) {
        _state.value = _state.value.copy(connected = connected)
    }

    fun onRead(platform: String, texts: List<String>, recognized: String?) {
        val now = System.currentTimeMillis()
        val s = _state.value
        val hasMoney = texts.any { it.contains("R$") }
        _state.value = s.copy(
            connected = true,
            lastEventAt = now,
            lastPlatform = platform,
            lastTextCount = texts.size,
            lastMoneyTexts = if (hasMoney) texts.take(MAX_TEXTS) else s.lastMoneyTexts,
            lastMoneyAt = if (hasMoney) now else s.lastMoneyAt,
            lastMoneyRecognized = if (hasMoney) recognized != null else s.lastMoneyRecognized,
            lastOffer = recognized ?: s.lastOffer,
            lastOfferAt = if (recognized != null) now else s.lastOfferAt,
        )
    }

    /** Texto para colar no chat/WhatsApp: plataforma, resultado e os textos lidos. */
    fun report(): String {
        val s = _state.value
        return buildString {
            appendLine("MApp – leitura (${s.lastPlatform ?: "?"})")
            appendLine("Leitor conectado: ${if (s.connected) "sim" else "não"}")
            appendLine("Reconhecida como oferta: ${if (s.lastMoneyRecognized) "sim" else "não"}")
            s.lastOffer?.let { appendLine("Última oferta: $it") }
            appendLine("Textos:")
            s.lastMoneyTexts.forEach { appendLine("• $it") }
        }
    }

    /** "há 5 s", "há 3 min", "nunca". */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0L) return "nunca"
        val sec = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "há $sec s"
            sec < 3600 -> "há ${sec / 60} min"
            else -> "há ${sec / 3600} h"
        }
    }

    private const val MAX_TEXTS = 150
}
