package br.com.mapp.reader

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.reader.parser.OcrLayout
import br.com.mapp.reader.parser.OfferParsers
import br.com.mapp.reader.parser.TripScreens
import br.com.mapp.reader.parser.TripStageTracker
import br.com.mapp.core.model.TripEvent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lê (SOMENTE LÊ) a tela dos apps de corrida e publica as ofertas no [OfferBus].
 *
 * Duas fontes:
 *  1. TEXTO — a árvore de Acessibilidade, a cada evento do app de corrida.
 *  2. IMAGEM — quando o app não entrega o cartão da oferta como texto (caso da Uber), captura a
 *     janela do app e reconhece o texto no aparelho ([ScreenOcr]). Só com o copiloto ligado.
 * O [OfferTracker] junta as duas e decide o que está na tela.
 *
 * Proibido: performAction, dispatchGesture ou qualquer outra interação com a tela.
 *
 * Threads: o estado do [tracker] vive na main thread ([handler]). O trabalho pesado — percorrer a
 * árvore de Acessibilidade (chamadas ao processo do app de corrida) e interpretar o texto do OCR —
 * roda na thread [worker]. A main thread é a mesma do cartão flutuante: travá-la trava o semáforo.
 */
class OfferAccessibilityService : AccessibilityService() {

    private class Target(val platform: Platform, val windowId: Int, val bounds: Rect, val layer: Int)

    private class Scene(val target: Target?, val ownWindows: List<Rect>, val summary: List<String>)

    private val handler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private val tracker = OfferTracker(
        windowSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        publish = { offer ->
            if (debugCapture) Log.d(TAG, "Oferta publicada: ${summary(offer)}")
            OfferBus.publish(offer)
        },
        clear = { OfferBus.clear() },
    )

    /** Corrida aceita/finalizada (main thread): só avisa na troca de etapa. */
    private val stages = TripStageTracker()

    // ---- Texto: cache da última leitura (só no [worker]; textos iguais não são reprocessados).
    private var lastPlatform: Platform? = null
    private var lastTexts: List<String>? = null
    private var lastParsed: RawOffer? = null

    // ---- Texto: agendamento (main thread). Eventos em rajada viram uma leitura a cada TREE_MIN_INTERVAL_MS.
    private var scanPlatform: Platform? = null
    private var scanQueued = false
    private var scanRunning = false
    private var scanAgain = false
    private var lastScanAt = 0L

    // ---- Imagem (OCR).
    private var ocr: ScreenOcr? = null
    private var ocrBusy = false
    private var ocrStartedAt = 0L
    private var ocrDueAt = 0L
    private var lastCycleAt = 0L
    /** Pacote de cada janela (usado nas duas threads). */
    private val windowPackages = ConcurrentHashMap<Int, String>()

    /** Relê o texto: limpa a oferta se ela sumiu sem gerar evento. */
    private val recheck = Runnable {
        scanPlatform?.takeIf { tracker.source == OfferTracker.Source.TREE }?.let { requestScan(it, urgent = true) }
    }

    /** Lê a árvore no [worker] e devolve o resultado ao [tracker] na main thread. */
    private val runScan = Runnable {
        scanQueued = false
        val platform = scanPlatform ?: return@Runnable
        val bg = worker ?: return@Runnable
        scanRunning = true
        lastScanAt = SystemClock.elapsedRealtime()
        bg.post {
            var ok = true
            val offer = try {
                readTree(platform)
            } catch (e: Exception) {
                ok = false
                Log.w(TAG, "Falha ao ler a tela", e)
                null
            }
            handler.post {
                scanRunning = false
                if (ok) safely { onTreeResult(offer) }
                if (scanAgain) {
                    scanAgain = false
                    requestScan(platform, urgent = false)
                }
            }
        }
    }

    /** Laço da leitura por imagem: uma falha num ciclo não pode parar os próximos. */
    private val ocrTick = Runnable {
        var next = OCR_POLL_MS
        try {
            next = tick()
        } catch (e: Exception) {
            Log.w(TAG, "Falha na leitura por imagem", e)
        }
        if (ocr != null) scheduleTick(next)
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("MAppReader").also { it.start() }
        workerThread = thread
        worker = Handler(thread.looper)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Leitor de ofertas conectado")
        ReaderStatus.setConnected(true)
        _windowContext.value = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val engine = try {
                ScreenOcr(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Leitura por imagem indisponível", t)
                null
            }
            ocr = engine
            if (engine != null) {
                ReaderStatus.setOcr(true, modesLabel(tracker.modes()))
                scheduleTick(OCR_POLL_MS)
            } else {
                ReaderStatus.setOcr(false, "indisponível (falha ao iniciar o reconhecimento de texto)")
            }
        } else {
            ReaderStatus.setOcr(false, "indisponível neste Android (precisa do 11 ou mais novo)")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ReaderStatus.setConnected(false)
        if (_windowContext.value === this) _windowContext.value = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val platform = Platform.fromPackage(event?.packageName?.toString()) ?: return
        // Troca de tela (oferta chegando/saindo) é urgente; mudança de conteúdo vem em rajada
        // (contagem regressiva, animações) e é agrupada.
        val screenChanged = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        safely {
            requestScan(platform, urgent = screenChanged)
            // Algo mudou no app de corrida: adianta a próxima leitura por imagem.
            requestOcrSoon(screenChanged)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        worker = null
        workerThread = null
        ocr?.close()
        ocr = null
        tracker.reset()
        stages.reset()
        ReaderStatus.setConnected(false)
        if (_windowContext.value === this) _windowContext.value = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ fonte 1: texto

    /** Pede uma leitura do texto. [urgent] = já; senão respeita o intervalo mínimo entre leituras. */
    private fun requestScan(platform: Platform, urgent: Boolean) {
        scanPlatform = platform
        if (scanRunning) {
            scanAgain = true
            return
        }
        if (scanQueued) {
            if (!urgent) return
            handler.removeCallbacks(runScan)
        }
        val wait = if (urgent) 0L else (lastScanAt + TREE_MIN_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        scanQueued = true
        handler.postDelayed(runScan, wait)
    }

    /** Main thread: entrega a leitura ao [tracker] e agenda a reconferência. */
    private fun onTreeResult(offer: RawOffer?) {
        handler.removeCallbacks(recheck)
        val recheckIn = tracker.onTree(offer, SystemClock.elapsedRealtime())
        if (recheckIn != null) handler.postDelayed(recheck, recheckIn.coerceAtLeast(50L))
    }

    /** Roda no [worker]: percorre a árvore e interpreta. */
    private fun readTree(platform: Platform): RawOffer? {
        val texts = collectTexts(platform.packageName)

        return if (platform == lastPlatform && texts == lastTexts) {
            lastParsed
        } else {
            if (debugCapture && texts.isNotEmpty()) {
                // Contém endereços: só para calibração local (Logcat), nunca enviar a lugar nenhum.
                Log.d(TAG, "[${platform.label}] " + texts.joinToString(" | "))
            }
            val parsed = if (texts.isEmpty()) null else OfferParsers.forPlatform(platform).parse(texts)
            if (parsed == null && texts.isNotEmpty()) {
                val stage = TripScreens.detect(texts)
                handler.post { onStage(stage) }
            }
            ReaderStatus.onRead(ReaderStatus.SOURCE_TREE, platform.label, texts, parsed?.let(::summary))
            lastPlatform = platform
            lastTexts = texts
            lastParsed = parsed
            parsed
        }
    }

    /** Main thread: tela de corrida aceita/finalizada vira evento para a jornada. */
    private fun onStage(stage: TripEvent?) {
        safely {
            stages.onScreen(stage, SystemClock.elapsedRealtime())?.let {
                if (debugCapture) Log.d(TAG, "Etapa da corrida: $it")
                OfferBus.publishTripStage(it)
            }
        }
    }

    private fun summary(o: RawOffer): String = buildString {
        append(String.format(PT_BR, "R$ %.2f", o.price))
        o.pickupKm?.let { append(" · busca $it km") }
        o.pickupMin?.let { append(" / $it min") }
        o.tripKm?.let { append(" · viagem $it km") }
        o.tripMin?.let { append(" / $it min") }
        o.pickupAddress?.let { append(" · de: $it") }
        o.dropoffAddress?.let { append(" · para: $it") }
    }

    // ------------------------------------------------------------------ fonte 2: imagem

    private fun scheduleTick(delayMs: Long) {
        handler.removeCallbacks(ocrTick)
        val delay = delayMs.coerceAtLeast(50L)
        ocrDueAt = SystemClock.elapsedRealtime() + delay
        handler.postDelayed(ocrTick, delay)
    }

    /** Evento do app de corrida: se a próxima leitura por imagem está longe, traz para perto. */
    private fun requestOcrSoon(screenChanged: Boolean) {
        if (ocr == null || ocrBusy || !OfferBus.hasListeners) return
        // Com a oferta já no cartão, a animação da contagem não deve disparar OCR sem parar;
        // só troca de tela (oferta aceita/recusada/sumiu) adianta a conferência.
        if (tracker.shown != null && !screenChanged) return
        val now = SystemClock.elapsedRealtime()
        val due = maxOf(now + OCR_AFTER_EVENT_MS, lastCycleAt + OCR_MIN_INTERVAL_MS)
        if (due + 50L < ocrDueAt) scheduleTick(due - now)
    }

    /** Um passo do laço. Devolve em quanto tempo rodar de novo. */
    private fun tick(): Long {
        val engine = ocr ?: return OCR_IDLE_MS
        val now = SystemClock.elapsedRealtime()
        if (!OfferBus.hasListeners || !isScreenOn()) {
            // Copiloto parado ou tela apagada: sem captura.
            tracker.onPaused()
            return OCR_IDLE_MS
        }
        if (ocrBusy) {
            if (now - ocrStartedAt > OCR_TIMEOUT_MS) ocrBusy = false
            return OCR_BUSY_RETRY_MS
        }
        tracker.onTick(now)
        // O texto já entregou a oferta completa: não precisa da imagem agora.
        if (tracker.treeHasIt) return OCR_POLL_MS
        val sinceLast = now - lastCycleAt
        if (sinceLast < OCR_MIN_INTERVAL_MS) return OCR_MIN_INTERVAL_MS - sinceLast
        return startCycle(engine, now)
    }

    /** Captura a janela do app de corrida e lê o texto da imagem. Devolve o tempo até o próximo ciclo. */
    private fun startCycle(engine: ScreenOcr, now: Long): Long {
        val scene = findScene()
        ReaderStatus.onWindows(scene.summary)
        val target = scene.target
        if (target == null) {
            // Nenhuma janela de app de corrida na tela: uma oferta lida por imagem não está mais lá.
            val wasShown = tracker.shownByImage
            tracker.onNoTarget()
            return if (wasShown) OCR_POLL_MS else OCR_NO_TARGET_MS
        }
        lastCycleAt = now
        val backupOnly = tracker.isTreeProven(target.platform)
        val modes = tracker.beginCycle(backupOnly)
        ReaderStatus.setOcr(true, modesLabel(modes))
        ocrBusy = true
        ocrStartedAt = now
        captureNext(engine, scene, target, modes, 0)
        return when {
            backupOnly && tracker.shown == null -> OCR_BACKUP_POLL_MS
            tracker.shownByImage -> OCR_SHOWN_POLL_MS
            modes.size > 1 -> OCR_POLL_TWO_MODES_MS
            else -> OCR_POLL_MS
        }
    }

    private fun captureNext(
        engine: ScreenOcr,
        scene: Scene,
        target: Target,
        modes: List<OfferTracker.Mode>,
        index: Int,
    ) {
        val mode = modes[index]
        engine.capture(mode, target.windowId, target.bounds, scene.ownWindows) { result ->
            safely { onOcrResult(target.platform, mode, result) }
            if (index + 1 < modes.size) {
                // O sistema recusa capturas com menos de ~1/3 s de intervalo.
                handler.postDelayed(
                    { safely { captureNext(engine, scene, target, modes, index + 1) } },
                    OCR_BETWEEN_MODES_MS,
                )
            } else {
                ocrBusy = false
            }
        }
    }

    private fun onOcrResult(platform: Platform, mode: OfferTracker.Mode, result: ScreenOcr.Result) {
        when (result) {
            is ScreenOcr.Result.Error -> {
                ReaderStatus.onOcrError(mode.label, result.reason)
                tracker.onImageError(mode)
            }
            is ScreenOcr.Result.Ok -> {
                val bg = worker ?: return
                bg.post {
                    // Interpretação fora da main thread (é a mesma do cartão flutuante).
                    val parser = OfferParsers.forPlatform(platform)
                    val (texts, offer) = try {
                        val lines = OcrLayout.toTexts(result.lines, result.excluded)
                        val parsed = if (lines.isEmpty()) null else parser.parse(lines, fromOcr = true)
                        if (parsed == null) {
                            val stage = TripScreens.detect(lines)
                            handler.post { onStage(stage) }
                        }
                        ReaderStatus.onRead(
                            ReaderStatus.SOURCE_OCR + "/" + mode.label,
                            platform.label,
                            lines,
                            parsed?.let(::summary),
                            result.millis,
                        )
                        lines to parsed
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao interpretar a imagem", e)
                        return@post
                    }
                    handler.post {
                        safely {
                            tracker.onImage(mode, offer, SystemClock.elapsedRealtime()) { shown ->
                                // Preço coberto por outra janela, mas os trechos da oferta continuam na tela?
                                parser.stillShows(texts, shown)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun modesLabel(modes: List<OfferTracker.Mode>): String =
        modes.joinToString(" + ") { it.label } + if (modes.size > 1) " (testando os dois)" else ""

    private fun isScreenOn(): Boolean =
        (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive != false

    /**
     * Janelas na tela: a maior/mais alta do app de corrida é o alvo da captura; as do próprio MApp
     * (cartão, ícone) são ignoradas na imagem. Outras janelas só entram no resumo do diagnóstico.
     */
    private fun findScene(): Scene {
        val list = try {
            windows
        } catch (e: Exception) {
            emptyList()
        }
        val metrics = resources.displayMetrics
        val minArea = metrics.widthPixels.toLong() * metrics.heightPixels * MIN_TARGET_AREA_PERCENT / 100
        var best: Target? = null
        val own = ArrayList<Rect>()
        val summary = ArrayList<String>()
        val alive = HashSet<Int>()

        for (window in list) {
            val id = window.id
            alive += id
            val pkg = windowPackages[id] ?: packageOf(window)?.also { windowPackages[id] = it }
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            val platform = Platform.fromPackage(pkg)
            val who = when {
                platform != null -> platform.label
                pkg == packageName -> "MApp"
                pkg == null -> "sem conteúdo"
                else -> "outro app"
            }
            summary += "$who ${bounds.width()}x${bounds.height()} em ${bounds.left},${bounds.top} " +
                "(camada ${window.layer}, tipo ${window.type})"
            // Cartão e ícone do MApp são janelas da Acessibilidade (às vezes sem conteúdo legível).
            if (pkg == packageName || window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) own += bounds
            if (platform != null && bounds.width().toLong() * bounds.height() >= minArea) {
                val previous = best
                if (previous == null || window.layer > previous.layer) {
                    best = Target(platform, id, bounds, window.layer)
                }
            }
        }
        windowPackages.keys.retainAll(alive)
        return Scene(best, own, summary)
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? {
        val root = try {
            window.root
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            recycle(root)
        }
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
            // Janela de outro app já conhecida: nem abre (cada abertura é uma chamada entre processos).
            val known = windowPackages[window.id]
            if (window.id in seenWindows || (known != null && known != packageName)) continue
            val root = window.root ?: continue
            try {
                val pkg = root.packageName?.toString()
                if (pkg != null) windowPackages[window.id] = pkg
                if (root.windowId !in seenWindows && pkg == packageName) {
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

        private val _windowContext = MutableStateFlow<Context?>(null)

        /**
         * Contexto do serviço conectado (null = desligado). O cartão e o ícone "M" usam o
         * WindowManager dele para abrir janelas TYPE_ACCESSIBILITY_OVERLAY: a Uber esconde as
         * sobreposições comuns (TYPE_APPLICATION_OVERLAY) enquanto a solicitação está na tela.
         */
        val windowContext: StateFlow<Context?> = _windowContext.asStateFlow()
        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

        private const val MAX_DEPTH = 60
        private const val MAX_NODES = 2_000

        // ---- Imagem
        /** Intervalo entre leituras por imagem com o app de corrida na tela. */
        private const val OCR_POLL_MS = 1_200L

        /** Enquanto testa os dois modos de captura (duas leituras por ciclo), dá mais folga ao aparelho. */
        private const val OCR_POLL_TWO_MODES_MS = 1_800L

        /** Oferta já no cartão: a imagem só confere se ela continua lá (menos CPU disputando com a Uber). */
        private const val OCR_SHOWN_POLL_MS = 1_500L

        /** Texto: intervalo mínimo entre leituras da árvore durante rajadas de eventos. */
        private const val TREE_MIN_INTERVAL_MS = 350L

        /** Quando o texto já funciona para o app, a imagem só confere de vez em quando. */
        private const val OCR_BACKUP_POLL_MS = 4_000L

        /** Sem janela de app de corrida na tela: só olha as janelas, sem capturar. */
        private const val OCR_NO_TARGET_MS = 2_500L

        /** Copiloto parado ou tela apagada. */
        private const val OCR_IDLE_MS = 5_000L
        private const val OCR_AFTER_EVENT_MS = 250L
        private const val OCR_MIN_INTERVAL_MS = 700L
        private const val OCR_BETWEEN_MODES_MS = 400L
        private const val OCR_BUSY_RETRY_MS = 300L
        private const val OCR_TIMEOUT_MS = 6_000L

        /** Janela do app de corrida menor que isto (% da tela) não é cartão — ex.: o ícone flutuante da Uber. */
        private const val MIN_TARGET_AREA_PERCENT = 12

        /**
         * Liga o log (Logcat, tag "MAppReader") do texto bruto lido de cada tela. Use para capturar
         * textos reais e transformá-los em casos de teste dos parsers. Deixe desligado no dia a dia.
         */
        @Volatile
        var debugCapture: Boolean = false
    }
}
