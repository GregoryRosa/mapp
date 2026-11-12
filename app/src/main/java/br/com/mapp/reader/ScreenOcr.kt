package br.com.mapp.reader

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import br.com.mapp.reader.parser.OcrBox
import br.com.mapp.reader.parser.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lê o texto da IMAGEM do app de corrida: captura a tela pelo serviço de Acessibilidade e reconhece
 * o texto no próprio aparelho (ML Kit com modelo embutido). Usado quando o app de corrida não entrega
 * o cartão da oferta como texto.
 *
 * A imagem fica só na memória durante o reconhecimento e é descartada: nunca é salva nem enviada.
 * Só captura — não toca nem interage com a tela.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class ScreenOcr(private val service: AccessibilityService) {

    sealed class Result {
        /** [lines] e [excluded] estão nas coordenadas da imagem analisada. */
        class Ok(val lines: List<OcrLine>, val excluded: List<OcrBox>, val millis: Long) : Result()
        class Error(val reason: String) : Result()
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var closed = false

    /**
     * Captura e reconhece. [OfferTracker.Mode.WINDOW] (Android 14+) pega só a janela [windowId];
     * [OfferTracker.Mode.DISPLAY] pega a tela inteira e recorta em [bounds] (a área da janela do app de
     * corrida), ignorando [ownWindows] (janelas do próprio MApp). [callback] é chamado uma vez, na main thread.
     */
    fun capture(
        mode: OfferTracker.Mode,
        windowId: Int,
        bounds: Rect,
        ownWindows: List<Rect>,
        callback: (Result) -> Unit,
    ) {
        val started = SystemClock.elapsedRealtime()
        val useWindow = mode == OfferTracker.Mode.WINDOW &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val delivered = AtomicBoolean(false)
        val deliver: (Result) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) main.post { if (!closed) callback(result) }
        }

        val onScreenshot = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                // Roda no [worker]: conversão e recorte fora da main thread.
                try {
                    val full = toSoftwareBitmap(screenshot)
                    if (full == null) {
                        deliver(Result.Error("imagem vazia"))
                        return
                    }
                    val excluded = ArrayList<Rect>()
                    val cropped = if (useWindow) full else cropTo(full, bounds, ownWindows, excluded)
                    if (cropped == null) {
                        deliver(Result.Error("janela fora da tela"))
                        return
                    }
                    val scale = if (cropped.width > MAX_WIDTH) MAX_WIDTH.toFloat() / cropped.width else 1f
                    val image = if (scale < 1f) shrink(cropped, scale) else cropped
                    val boxes = excluded.map {
                        OcrBox(
                            (it.left * scale).toInt() - EXCLUDE_PAD,
                            (it.top * scale).toInt() - EXCLUDE_PAD,
                            (it.right * scale).toInt() + EXCLUDE_PAD,
                            (it.bottom * scale).toInt() + EXCLUDE_PAD,
                        )
                    }
                    recognize(image, boxes, started, deliver)
                } catch (t: Throwable) {
                    deliver(Result.Error("falha ao preparar a imagem (${t.javaClass.simpleName})"))
                }
            }

            override fun onFailure(errorCode: Int) {
                deliver(Result.Error("captura recusada (${errorName(errorCode)})"))
            }
        }

        try {
            if (useWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.takeScreenshotOfWindow(windowId, worker, onScreenshot)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, onScreenshot)
            }
        } catch (e: Exception) {
            deliver(Result.Error("captura indisponível (${e.javaClass.simpleName})"))
        }
    }

    fun close() {
        closed = true
        try {
            recognizer.close()
        } catch (e: Exception) {
            // já fechado
        }
        worker.shutdown()
    }

    private fun recognize(image: Bitmap, excluded: List<OcrBox>, started: Long, deliver: (Result) -> Unit) {
        recognizer.process(InputImage.fromBitmap(image, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        lines += OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                    }
                }
                image.recycle()
                deliver(Result.Ok(lines, excluded, SystemClock.elapsedRealtime() - started))
            }
            .addOnFailureListener { e ->
                image.recycle()
                deliver(Result.Error("reconhecimento falhou (${e.message ?: e.javaClass.simpleName})"))
            }
    }

    /** A captura chega como bitmap de GPU; o OCR precisa de uma cópia comum (ARGB_8888). */
    private fun toSoftwareBitmap(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            try {
                return hardware.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardware.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    /**
     * Recorta a tela inteira na área do app de corrida (não lê o que é de outros apps) e devolve em
     * [excluded] as janelas do próprio MApp que caem dentro do recorte.
     */
    private fun cropTo(full: Bitmap, bounds: Rect, ownWindows: List<Rect>, excluded: MutableList<Rect>): Bitmap? {
        val crop = Rect(bounds)
        if (!crop.intersect(0, 0, full.width, full.height) || crop.width() < MIN_SIDE || crop.height() < MIN_SIDE) {
            full.recycle()
            return null
        }
        for (own in ownWindows) {
            val r = Rect(own)
            if (r.intersect(crop)) {
                r.offset(-crop.left, -crop.top)
                excluded += r
            }
        }
        if (crop.width() == full.width && crop.height() == full.height) return full
        val part = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
        if (part !== full) full.recycle()
        return part
    }

    private fun shrink(source: Bitmap, scale: Float): Bitmap {
        val small = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (small !== source) source.recycle()
        return small
    }

    private fun errorName(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "erro interno"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "sem acesso de acessibilidade"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "capturas seguidas demais"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "tela inválida"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "janela inválida"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "janela protegida contra captura"
        else -> "código $code"
    }

    private companion object {
        /**
         * Largura máxima da imagem analisada: menos pixels = OCR mais rápido e menos bateria. Nos prints
         * reais o cartão da Uber continua legível até ~720 px de largura; 900 deixa folga.
         */
        const val MAX_WIDTH = 900
        const val MIN_SIDE = 50
        const val EXCLUDE_PAD = 4
    }
}
