package br.com.mapp.reader

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.reader.parser.OfferParsers

/**
 * Lê (SOMENTE LÊ) a tela dos apps de corrida e publica as ofertas no [OfferBus].
 *
 * Proibido: performAction, dispatchGesture ou qualquer outra interação com a tela.
 * Os callbacks rodam na main thread; o [handler] também, então não há concorrência no estado.
 */
class OfferAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // Cache da última leitura: textos iguais não são reprocessados.
    private var lastPlatform: Platform? = null
    private var lastTexts: List<String>? = null
    private var lastParsed: RawOffer? = null

    // Estado da oferta na tela.
    private var offerVisible = false
    private var lastOfferKey: RawOffer? = null
    private var lastOfferSeenAt = 0L

    /** Relê a tela depois de [CLEAR_AFTER_MS]: limpa a oferta se ela sumiu sem gerar evento. */
    private val recheck = Runnable {
        safely { lastPlatform?.takeIf { offerVisible }?.let { scan(it) } }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Leitor de ofertas conectado")
        ReaderStatus.setConnected(true)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ReaderStatus.setConnected(false)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val platform = Platform.fromPackage(event?.packageName?.toString()) ?: return
        safely { scan(platform) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (offerVisible) clearOffer()
        ReaderStatus.setConnected(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ fluxo

    private fun scan(platform: Platform) {
        val texts = collectTexts(platform.packageName)
        val now = SystemClock.elapsedRealtime()

        val offer = if (platform == lastPlatform && texts == lastTexts) {
            lastParsed
        } else {
            if (debugCapture && texts.isNotEmpty()) {
                // Contém endereços: só para calibração local (Logcat), nunca enviar a lugar nenhum.
                Log.d(TAG, "[${platform.label}] " + texts.joinToString(" | "))
            }
            val parsed = if (texts.isEmpty()) null else OfferParsers.forPlatform(platform).parse(texts)
            ReaderStatus.onRead(platform.label, texts, parsed?.let(::summary))
            lastPlatform = platform
            lastTexts = texts
            lastParsed = parsed
            parsed
        }

        if (offer != null) onOffer(offer, now) else onNoOffer(now)
    }

    private fun onOffer(offer: RawOffer, now: Long) {
        lastOfferSeenAt = now
        offerVisible = true
        // Debounce: mesma oferta (ignorando texto bruto e horário) não é republicada.
        val key = offer.copy(rawText = "", capturedAt = 0L)
        if (key != lastOfferKey) {
            lastOfferKey = key
            if (debugCapture) Log.d(TAG, "Oferta publicada: $key")
            OfferBus.publish(offer)
        }
        scheduleRecheck(CLEAR_AFTER_MS)
    }

    private fun onNoOffer(now: Long) {
        if (!offerVisible) return
        val elapsed = now - lastOfferSeenAt
        if (elapsed >= CLEAR_AFTER_MS) clearOffer() else scheduleRecheck(CLEAR_AFTER_MS - elapsed)
    }

    private fun scheduleRecheck(delayMs: Long) {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, delayMs.coerceAtLeast(50L))
    }

    private fun clearOffer() {
        handler.removeCallbacks(recheck)
        offerVisible = false
        lastOfferKey = null
        OfferBus.clear()
    }

    private fun summary(o: RawOffer): String = buildString {
        append(String.format(java.util.Locale.forLanguageTag("pt-BR"), "R$ %.2f", o.price))
        o.pickupKm?.let { append(" · busca $it km") }
        o.tripKm?.let { append(" · viagem $it km") }
        o.tripMin?.let { append(" / $it min") }
    }

    // ------------------------------------------------------------------ leitura da árvore

    /** Textos visíveis (text e contentDescription) das janelas do pacote, em ordem de leitura. */
    private fun collectTexts(packageName: String): List<String> {
        val out = ArrayList<String>(64)
        val seenWindows = HashSet<Int>()
        val budget = intArrayOf(MAX_NODES)

        rootInActiveWindow?.let { root ->
            try {
                if (root.packageName?.toString() == packageName) {
                    seenWindows += root.windowId
                    collect(root, out, 0, budget)
                }
            } finally {
                recycle(root)
            }
        }

        // Cartões de oferta podem vir em outra janela (sobreposição do próprio app de corrida).
        val windows = try {
            this.windows
        } catch (e: Exception) {
            emptyList()
        }
        for (window in windows) {
            val root = window.root ?: continue
            try {
                if (root.windowId !in seenWindows && root.packageName?.toString() == packageName) {
                    seenWindows += root.windowId
                    collect(root, out, 0, budget)
                }
            } finally {
                recycle(root)
            }
        }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, budget: IntArray) {
        if (depth > MAX_DEPTH || budget[0]-- <= 0) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) out += text
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc != text) out += desc
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, out, depth + 1, budget)
            } finally {
                recycle(child)
            }
        }
    }

    /** Antes do API 33 os nós vêm de um pool e devem ser reciclados; depois disso é no-op. */
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (e: IllegalStateException) {
                // já reciclado
            }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Nunca derrubar o serviço por causa de uma tela inesperada.
            Log.w(TAG, "Falha ao ler a tela", e)
        }
    }

    companion object {
        private const val TAG = "MAppReader"

        /** Sem oferta por este tempo = oferta sumiu → OfferBus.clear(). */
        const val CLEAR_AFTER_MS = 1_500L

        private const val MAX_DEPTH = 60
        private const val MAX_NODES = 2_000

        /**
         * Liga o log (Logcat, tag "MAppReader") do texto bruto lido de cada tela. Use para capturar
         * textos reais e transformá-los em casos de teste dos parsers. Deixe desligado no dia a dia.
         */
        @Volatile
        var debugCapture: Boolean = false
    }
}
