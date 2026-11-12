package br.com.mapp.reader

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import java.util.EnumMap
import java.util.EnumSet

/**
 * Decide qual oferta está "na tela" a partir das leituras das duas fontes do leitor:
 *  - TEXTO: árvore de Acessibilidade (exata, mas a Uber não entrega o cartão da corrida por ela);
 *  - IMAGEM: OCR da captura da tela (enxerga tudo, mas com ruído e sujeito a janelas por cima).
 *
 * Regras:
 *  - quem publicou a oferta é quem decide que ela sumiu;
 *  - na imagem, os trechos (tempo + km de busca e viagem) identificam a oferta: um quadro com o preço
 *    lido errado, ou coberto, não vira "oferta nova" (nada de repetir voz/cartão);
 *  - enquanto nenhum modo de captura provou que enxerga a oferta, usam-se os dois por ciclo; depois
 *    fica só o que funcionou (janela tem preferência).
 *
 * Kotlin puro (sem Android): testado na JVM em OfferTrackerTest.
 */
class OfferTracker(
    /** Captura só da janela do app de corrida disponível (Android 14+)? */
    private val windowSupported: Boolean,
    private val publish: (RawOffer) -> Unit,
    private val clear: () -> Unit,
) {
    enum class Source { TREE, IMAGE }

    enum class Mode(val label: String) {
        /** Só a janela do app de corrida: janelas por cima não atrapalham. */
        WINDOW("janela"),

        /** A tela inteira, recortada na área do app de corrida. */
        DISPLAY("tela"),
    }

    /** Oferta publicada (null = nenhuma na tela). */
    var shown: RawOffer? = null
        private set
    var source: Source? = null
        private set

    private var lastSeenAt = 0L
    private var imageMisses = 0
    private var framesPerCycle = 1

    /** Modo de captura que já enxergou uma oferta. Enquanto null, testa os dois a cada ciclo. */
    var lockedMode: Mode? = null
        private set
    private var windowFailures = 0
    private val hits = EnumMap<Mode, Int>(Mode::class.java)
    private val treeProven: MutableSet<Platform> = EnumSet.noneOf(Platform::class.java)

    val shownByImage: Boolean get() = shown != null && source == Source.IMAGE

    /** O texto já entregou a oferta completa: a imagem não precisa trabalhar agora. */
    val treeHasIt: Boolean get() = shown?.let { source == Source.TREE && complete(it) } == true

    /** Nesse app o texto já entregou uma oferta completa alguma vez: a imagem vira só reserva. */
    fun isTreeProven(platform: Platform): Boolean = platform in treeProven

    // ------------------------------------------------------------------ texto

    /**
     * Leitura do texto ([offer] = null se não há oferta). Devolve em quantos ms reler o texto para
     * confirmar que a oferta sumiu, ou null se não precisa.
     */
    fun onTree(offer: RawOffer?, now: Long): Long? {
        if (offer != null) {
            onOffer(offer, Source.TREE, now)
            return if (source == Source.TREE) TREE_CLEAR_AFTER_MS else null
        }
        if (shown == null || source != Source.TREE) return null
        val elapsed = now - lastSeenAt
        if (elapsed >= TREE_CLEAR_AFTER_MS) {
            clearOffer()
            return null
        }
        return TREE_CLEAR_AFTER_MS - elapsed
    }

    // ------------------------------------------------------------------ imagem

    /** Modos de captura a usar agora: o que já funcionou; antes disso, os dois (janela só no Android 14+). */
    fun modes(): List<Mode> {
        val windowOk = windowSupported && windowFailures < MAX_WINDOW_FAILURES
        return when {
            lockedMode == Mode.DISPLAY -> listOf(Mode.DISPLAY)
            lockedMode == Mode.WINDOW && windowOk -> listOf(Mode.WINDOW)
            windowOk -> listOf(Mode.WINDOW, Mode.DISPLAY)
            else -> listOf(Mode.DISPLAY)
        }
    }

    /** Começo de um ciclo de captura. [backupOnly] = o texto já funciona nesse app: um modo só. */
    fun beginCycle(backupOnly: Boolean): List<Mode> {
        val list = modes().let { if (backupOnly) it.take(1) else it }
        framesPerCycle = list.size
        return list
    }

    /**
     * Quadro lido por imagem. [offer] = oferta reconhecida ou null; [stillShows] diz se o quadro ainda
     * mostra a oferta dada mesmo sem dar para ler tudo (ex.: preço coberto por outra janela).
     */
    fun onImage(mode: Mode, offer: RawOffer?, now: Long, stillShows: (RawOffer) -> Boolean) {
        if (mode == Mode.WINDOW) windowFailures = 0
        val current = shown
        when {
            offer != null -> {
                hits[mode] = (hits[mode] ?: 0) + 1
                onOffer(offer, Source.IMAGE, now)
            }
            current != null && source == Source.IMAGE && stillShows(current) -> {
                lastSeenAt = now
                imageMisses = 0
            }
            else -> imageMiss(1)
        }
    }

    /** A captura falhou. Falha não conta como "oferta sumiu" — disso cuida [onTick]. */
    fun onImageError(mode: Mode) {
        if (mode != Mode.WINDOW) return
        windowFailures++
        // A captura por janela parou de funcionar: volta a usar a tela inteira.
        if (windowFailures >= MAX_WINDOW_FAILURES && lockedMode == Mode.WINDOW) lockedMode = null
    }

    /** Ciclo em que não há janela de app de corrida na tela. */
    fun onNoTarget() = imageMiss(framesPerCycle)

    /** Copiloto parado ou tela apagada: a oferta lida por imagem não pode ficar presa. */
    fun onPaused() {
        if (shownByImage) clearOffer()
    }

    /** A cada passo do laço: capturas falhando há muito tempo = não dá para saber se a oferta continua lá. */
    fun onTick(now: Long) {
        if (shownByImage && now - lastSeenAt > IMAGE_STALE_MS) clearOffer()
    }

    /** O leitor foi desligado. */
    fun reset() {
        if (shown != null) clearOffer()
    }

    // ------------------------------------------------------------------ regras

    private fun onOffer(offer: RawOffer, from: Source, now: Long) {
        val current = shown
        if (current != null && from != source) {
            // A outra fonte viu a mesma oferta: quem publicou continua no comando.
            if (sameOffer(current, offer, lenient = true)) return
            // Imagem no comando: o texto (incompleto nesse app) não troca a oferta.
            if (from == Source.TREE) return
            // Texto no comando e a imagem vê outra coisa: a imagem (regra mais rígida) assume.
        }
        lastSeenAt = now
        imageMisses = 0
        if (current != null && from == source && sameOffer(current, offer, lenient = from == Source.IMAGE)) {
            // Mesma oferta. Na imagem, um quadro mais completo (endereço que faltava) atualiza o cartão.
            val better = from == Source.IMAGE && offer.price == current.price &&
                completeness(offer) > completeness(current)
            if (better) show(offer, from)
        } else {
            show(offer, from)
        }
    }

    private fun show(offer: RawOffer, from: Source) {
        shown = offer
        source = from
        if (from == Source.TREE && complete(offer)) treeProven += offer.platform
        publish(offer)
    }

    /** Conta quadros de imagem sem a oferta; ela some depois de [IMAGE_CYCLES_TO_CLEAR] ciclos seguidos. */
    private fun imageMiss(frames: Int) {
        if (!shownByImage) return
        imageMisses += frames
        if (imageMisses >= IMAGE_CYCLES_TO_CLEAR * framesPerCycle) clearOffer()
    }

    private fun clearOffer() {
        // A oferta acabou: fica com o modo de captura que a enxergou (janela tem preferência).
        if (lockedMode == null) {
            lockedMode = when {
                (hits[Mode.WINDOW] ?: 0) > 0 -> Mode.WINDOW
                (hits[Mode.DISPLAY] ?: 0) > 0 -> Mode.DISPLAY
                else -> null
            }
        }
        hits.clear()
        shown = null
        source = null
        imageMisses = 0
        clear()
    }

    /**
     * Mesma oferta? Texto ([lenient] = false): tudo igual. Imagem: os trechos identificam a oferta —
     * o preço pode sair errado num quadro em que parte do cartão está coberta.
     */
    private fun sameOffer(a: RawOffer, b: RawOffer, lenient: Boolean): Boolean {
        if (!lenient) return a.copy(rawText = "", capturedAt = 0L) == b.copy(rawText = "", capturedAt = 0L)
        if (a.platform != b.platform) return false
        val sameLegs = a.pickupKm == b.pickupKm && a.pickupMin == b.pickupMin &&
            a.tripKm == b.tripKm && a.tripMin == b.tripMin
        val hasLegs = a.tripKm != null || a.pickupKm != null
        if (hasLegs) return sameLegs
        return sameLegs && a.price == b.price && a.pickupAddress == b.pickupAddress &&
            a.dropoffAddress == b.dropoffAddress
    }

    private fun completeness(o: RawOffer): Int = listOfNotNull(o.pickupAddress, o.dropoffAddress).size

    /** Busca e viagem com tempo e km: leitura completa. */
    private fun complete(o: RawOffer): Boolean =
        o.pickupKm != null && o.pickupMin != null && o.tripKm != null && o.tripMin != null

    companion object {
        /** Texto: sem oferta por este tempo = oferta sumiu. */
        const val TREE_CLEAR_AFTER_MS = 1_500L

        /** Imagem: ciclos seguidos sem ver a oferta = ela saiu da tela. */
        const val IMAGE_CYCLES_TO_CLEAR = 2

        /** Imagem: sem nenhum quadro bom por este tempo, a oferta é liberada. */
        const val IMAGE_STALE_MS = 8_000L
        const val MAX_WINDOW_FAILURES = 3
    }
}
