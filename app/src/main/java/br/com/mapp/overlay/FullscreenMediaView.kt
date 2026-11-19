package br.com.mapp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.Zone
import br.com.mapp.core.route.RouteStop
import br.com.mapp.maps.MapViews

/**
 * Mapa ou foto do Card em tela cheia: toque no quadro do Card abre, novo toque volta.
 * Janela própria por cima de tudo, aberta só enquanto aparece.
 * - Foto (rua ou satélite): a mesma imagem já carregada no Card, inteira — nenhum pedido a mais ao Google.
 * - Mapa: um mapa novo (tiles do cache) com a rota, que aceita pinça e arrastar; o toque simples volta.
 */
class FullscreenMediaView(private val host: OverlayHost) {
    private val context: Context = host.context
    private val windowManager: WindowManager = host.windowManager
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null

    val isShowing: Boolean get() = root != null

    /** Foto da coleta/parada/destino em tela cheia. */
    fun showPhoto(stop: RouteStop, bitmap: Bitmap, credit: String) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }
        val content = TapFrame(context, confirmTap = false).apply {
            setBackgroundColor(BG)
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(titleBar(stop), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            if (credit.isNotEmpty()) {
                addView(
                    text(11f, 0xDDFFFFFF.toInt()).apply {
                        text = credit
                        setPadding(dp(8), 0, dp(8), dp(4))
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                        .apply { bottomMargin = dp(40) },
                )
            }
            addView(hint("Toque para voltar"), hintParams())
            contentDescription = "${MapViews.titleOf(stop)} em tela cheia. Toque para voltar ao Card."
        }
        open(content)
    }

    /** Mapa da rota em tela cheia (pinça = zoom, arrastar = mover, toque = volta). */
    fun showMap(route: List<RouteStop>, zones: List<Zone>, current: GeoPoint?) {
        // Sai da janela = o próprio MapView se destrói (modo padrão do osmdroid).
        val map = MapViews.create(context, interactive = true)
        val content = TapFrame(context, confirmTap = true).apply {
            setBackgroundColor(BG)
            addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(hint("Toque para voltar · pinça para zoom"), hintParams())
            contentDescription = "Mapa da corrida em tela cheia. Toque para voltar ao Card."
        }
        if (!open(content)) return
        MapViews.renderOffer(map, zones, current, route, marginDp = 48)
    }

    /** Fecha (oferta sumiu, Card fechado/minimizado ou toque). */
    fun close() {
        val view = root ?: return
        root = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao fechar a tela cheia", e)
        }
    }

    private fun open(content: TapFrame): Boolean {
        close()
        // Fecha depois do toque (não no meio da entrega do evento) e só se ainda for esta tela.
        content.onTap = { main.post { if (root === content) close() } }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            host.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "MApp – tela cheia"
        }
        return try {
            windowManager.addView(content, params)
            root = content
            true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir a tela cheia", e)
            false
        }
    }

    /** Letra do mapa (cor do marcador) + "Coleta"/"Destino" e o endereço lido. */
    private fun titleBar(stop: RouteStop): View {
        val badge = text(14f, 0xFFFFFFFF.toInt(), bold = true).apply {
            text = stop.letter
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MapViews.colorOf(stop.kind))
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(16f, 0xFFFFFFFF.toInt(), bold = true).apply { text = MapViews.titleOf(stop) })
            stop.address?.takeIf { it.isNotBlank() }?.let { address ->
                addView(text(13f, 0xFFDDDDDD.toInt()).apply { text = address })
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(16), dp(36), dp(16), dp(12))
            addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun hint(message: String) = text(13f, 0xFFFFFFFF.toInt()).apply {
        text = message
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            setColor(0xAA000000.toInt())
            cornerRadius = 16f * density
        }
    }

    private fun hintParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { bottomMargin = dp(48) }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    /**
     * Toque simples em qualquer lugar = [onTap]; os toques seguem para o conteúdo (pinça e arrastar
     * no mapa). [confirmTap]: espera ver que não é toque duplo (zoom do mapa) antes de voltar.
     */
    @SuppressLint("ViewConstructor")
    private class TapFrame(context: Context, confirmTap: Boolean) : FrameLayout(context) {
        var onTap: () -> Unit = {}
        private val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!confirmTap) onTap()
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (confirmTap) onTap()
                    return true
                }
            },
        )

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            detector.onTouchEvent(ev)
            super.dispatchTouchEvent(ev)
            return true
        }
    }

    private companion object {
        const val TAG = "MApp/FullMedia"
        val BG = 0xF2000000.toInt()
    }
}
