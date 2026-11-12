package br.com.mapp.reader

import android.content.Context
import android.os.Build
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.reader.parser.ParseUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico do leitor (só em memória, nunca sai do aparelho). Mostra na tela inicial e no ícone
 * flutuante se a Acessibilidade está conectada e como anda a leitura. [report] monta um relatório
 * completo (guarda a tela da corrida mesmo depois que ela some), sem botão no app por enquanto.
 */
object ReaderStatus {

    /** Texto lido da árvore de Acessibilidade. */
    const val SOURCE_TREE = "texto"

    /** Texto reconhecido na imagem da tela (OCR); o modo de captura vem depois da barra. */
    const val SOURCE_OCR = "imagem"

    data class Snapshot(
        /** Serviço de Acessibilidade ligado e conectado pelo sistema. */
        val connected: Boolean = false,
        /** Última leitura de um app de corrida (System.currentTimeMillis), 0 = nunca. */
        val lastReadAt: Long = 0L,
        val lastPlatform: String? = null,
        val lastSource: String? = null,
        /** Leitura por imagem disponível neste aparelho? Se não, [ocrMode] diz o motivo. */
        val ocrAvailable: Boolean = false,
        val ocrMode: String = "iniciando",
        val ocrFrames: Int = 0,
        val lastOcrAt: Long = 0L,
        val lastOcrMillis: Long = 0L,
        val ocrError: String? = null,
        /** Última tela com cara de corrida (botão Aceitar ou dois trechos) e se virou oferta. */
        val lastOfferScreenAt: Long = 0L,
        val lastOfferScreenRecognized: Boolean = false,
        /** Última oferta reconhecida (resumo legível). */
        val lastOffer: String? = null,
        val lastOfferAt: Long = 0L,
    )

    private class Reading(
        val at: Long,
        val source: String,
        val platform: String,
        val recognized: String?,
        /** Quantas linhas a leitura tinha (em [texts] ficam só as primeiras). */
        val count: Int,
        val texts: List<String>,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // Telas que pareciam corrida e NÃO viraram oferta (o que mais interessa para calibrar).
    private val strongMisses = ArrayDeque<Reading>()
    // Telas com um trecho + valor que não viraram oferta (ex.: navegação) — contexto.
    private val weakMisses = ArrayDeque<Reading>()
    // Última leitura de cada fonte ("texto", "imagem/janela", "imagem/tela").
    private val latest = LinkedHashMap<String, Reading>()
    private var lastOfferReading: Reading? = null
    private var windows: List<String> = emptyList()
    // Último erro de captura de cada modo (o estado da tela só guarda o erro até a próxima leitura boa).
    private val captureErrors = LinkedHashMap<String, Pair<Long, String>>()

    @Synchronized
    fun setConnected(connected: Boolean) {
        _state.value = _state.value.copy(connected = connected)
    }

    @Synchronized
    fun setOcr(available: Boolean, mode: String) {
        val s = _state.value
        if (s.ocrAvailable != available || s.ocrMode != mode) _state.value = s.copy(ocrAvailable = available, ocrMode = mode)
    }

    @Synchronized
    fun onWindows(summary: List<String>) {
        windows = summary
    }

    @Synchronized
    fun onOcrError(mode: String, reason: String) {
        captureErrors[mode] = System.currentTimeMillis() to reason
        val message = "$mode: $reason"
        if (_state.value.ocrError != message) _state.value = _state.value.copy(ocrError = message)
    }

    /**
     * Registra uma leitura. [source] = [SOURCE_TREE] ou "[SOURCE_OCR]/modo"; [recognized] = resumo da
     * oferta ou null; [millis] = tempo da captura + reconhecimento (só imagem).
     */
    @Synchronized
    fun onRead(source: String, platform: String, texts: List<String>, recognized: String?, millis: Long = 0L) {
        val now = System.currentTimeMillis()
        val reading = Reading(
            now, source, platform, recognized, texts.size,
            texts.take(MAX_TEXTS).map { it.take(MAX_CHARS) },
        )
        val fromOcr = source.startsWith(SOURCE_OCR)

        val legLines = texts.count { raw ->
            val line = ParseUtils.cleanOcrLine(raw)
            ParseUtils.findDurations(line).isNotEmpty() && ParseUtils.findDistances(line).isNotEmpty()
        }
        val offerScreen = legLines >= 2 || texts.any { ParseUtils.isAcceptLine(it) }
        when {
            recognized != null -> lastOfferReading = reading
            offerScreen -> push(strongMisses, reading, MAX_STRONG)
            legLines >= 1 && texts.any { ParseUtils.hasMoney(it) } -> push(weakMisses, reading, MAX_WEAK)
        }
        latest[source] = reading

        val s = _state.value
        val isOfferScreen = offerScreen || recognized != null
        _state.value = s.copy(
            connected = true,
            lastReadAt = now,
            lastPlatform = platform,
            lastSource = source,
            ocrFrames = if (fromOcr) s.ocrFrames + 1 else s.ocrFrames,
            lastOcrAt = if (fromOcr) now else s.lastOcrAt,
            lastOcrMillis = if (fromOcr) millis else s.lastOcrMillis,
            ocrError = if (fromOcr) null else s.ocrError,
            lastOfferScreenAt = if (isOfferScreen) now else s.lastOfferScreenAt,
            lastOfferScreenRecognized = if (isOfferScreen) recognized != null else s.lastOfferScreenRecognized,
            lastOffer = recognized ?: s.lastOffer,
            lastOfferAt = if (recognized != null) now else s.lastOfferAt,
        )
    }

    /** Linhas curtas de estado para a tela inicial e o painel do ícone flutuante. */
    fun lines(s: Snapshot, copilotRunning: Boolean): List<String> = buildList {
        add(if (s.connected) "✅ Leitor conectado" else "⚠️ Leitor DESLIGADO – ative a Acessibilidade")
        add("Última leitura: ${s.lastPlatform ?: "—"} ${ago(s.lastReadAt)}" + (s.lastSource?.let { " ($it)" } ?: ""))
        add(
            "Leitura por imagem: " + when {
                !s.ocrAvailable -> s.ocrMode
                !copilotRunning -> "pausada (copiloto parado)"
                s.ocrFrames == 0 && s.ocrError != null -> "ERRO – ${s.ocrError}"
                s.ocrFrames == 0 -> "esperando o app de corrida aparecer na tela"
                else -> "${s.ocrMode} · ${ago(s.lastOcrAt)} · ${s.lastOcrMillis} ms" +
                    (s.ocrError?.let { " · erro: $it" } ?: "")
            },
        )
        if (s.lastOfferScreenAt > 0) {
            add(
                "Última tela de corrida: ${ago(s.lastOfferScreenAt)} → " +
                    if (s.lastOfferScreenRecognized) "reconhecida" else "NÃO reconhecida",
            )
        }
        s.lastOffer?.let { add("Última oferta: $it (${ago(s.lastOfferAt)})") }
    }

    /** Texto para colar no chat: aparelho, estado e as telas lidas (as de corrida ficam guardadas). */
    @Synchronized
    fun report(context: Context): String = buildString {
        val s = _state.value
        val running = OfferBus.hasListeners
        appendLine("MApp – diagnóstico · versão ${versionOf(context)}")
        appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Copiloto ativo: ${if (running) "sim" else "não"}")
        lines(s, running).forEach { appendLine(it) }
        appendLine("Quadros de imagem lidos: ${s.ocrFrames}")
        for ((mode, error) in captureErrors) {
            appendLine("Último erro de captura ($mode, ${CLOCK.format(Date(error.first))}): ${error.second}")
        }
        if (windows.isNotEmpty()) {
            appendLine("Janelas na última captura:")
            windows.forEach { appendLine("  $it") }
        }
        section("Tela de corrida NÃO reconhecida", strongMisses.toList().takeLast(2))
        section("Última oferta reconhecida", listOfNotNull(lastOfferReading))
        section("Tela com trecho e valor que não era oferta", weakMisses.toList().takeLast(1))
        section("Última leitura de cada fonte", latest.values.sortedBy { it.at })
    }

    private fun StringBuilder.section(title: String, readings: List<Reading>) {
        if (readings.isEmpty()) return
        appendLine()
        appendLine("== $title ==")
        for (r in readings) {
            appendLine(
                "[${CLOCK.format(Date(r.at))} ${r.source} ${r.platform}, ${r.count} linhas] " +
                    (r.recognized ?: "sem oferta"),
            )
            r.texts.forEach { appendLine("• $it") }
        }
    }

    private fun push(queue: ArrayDeque<Reading>, reading: Reading, max: Int) {
        val last = queue.peekLast()
        // Mesma tela lida de novo pela mesma fonte: troca pela mais nova em vez de empilhar.
        if (last != null && last.source == reading.source && last.texts == reading.texts) queue.removeLast()
        queue.addLast(reading)
        while (queue.size > max) queue.removeFirst()
    }

    /** Versão instalada (muda a cada APK gerado), para conferir se a atualização entrou. */
    fun versionOf(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
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

    private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private const val MAX_TEXTS = 60
    private const val MAX_CHARS = 100
    private const val MAX_STRONG = 4
    private const val MAX_WEAK = 2
}
