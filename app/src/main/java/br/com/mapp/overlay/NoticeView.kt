package br.com.mapp.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Avisos do copiloto ("Corrida aceita", "Corrida finalizada"...) no lugar do Toast, que some em
 * 2 s e não deixa tempo de ler dirigindo. Cada aviso fica o tempo pedido; vários avisos juntos
 * empilham (o mais novo embaixo) em vez de um apagar o outro. Abre na mesma janela do cartão e do
 * ícone "M" ([OverlayHost]) e não recebe toques (o dedo passa para o app de corrida).
 */
class NoticeView(private val host: OverlayHost) {

    private val context: Context = host.context
    private val windowManager: WindowManager = host.windowManager
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var destroyed = false

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        host.type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(BOTTOM_OFFSET_DP)
        windowAnimations = android.R.style.Animation_Toast
        title = "MApp – aviso"
    }

    /** Mostra [message] por [durationMs]. O mesmo texto já na tela só ganha mais tempo. */
    fun show(message: String, durationMs: Long) {
        if (destroyed) return
        val existing = (0 until box.childCount).map { box.getChildAt(it) }
            .firstOrNull { (it as? TextView)?.text?.toString() == message }
        val pill = existing ?: makePill(message).also {
            while (box.childCount >= MAX_VISIBLE) remove(box.getChildAt(0))
            box.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (box.childCount == 0) 0 else dp(6) })
        }
        handler.removeCallbacksAndMessages(pill)
        handler.postAtTime({ remove(pill) }, pill, SystemClock.uptimeMillis() + durationMs)
        attach()
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        box.removeAllViews()
        detach()
    }

    private fun remove(view: View) {
        handler.removeCallbacksAndMessages(view)
        box.removeView(view)
        if (box.childCount == 0) detach()
    }

    private fun attach() {
        if (attached) return
        try {
            windowManager.addView(box, params)
            attached = true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível mostrar o aviso", e)
        }
    }

    private fun detach() {
        if (!attached) return
        try {
            windowManager.removeViewImmediate(box)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao remover aviso", e)
        }
        attached = false
    }

    private fun makePill(message: String) = TextView(context).apply {
        text = message
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        maxWidth = context.resources.displayMetrics.widthPixels - dp(32)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            setColor(BG)
            cornerRadius = 22f * density
            setStroke(dp(1), BORDER)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val TAG = "MApp/Notice"
        const val MAX_VISIBLE = 3
        const val BOTTOM_OFFSET_DP = 110
        val BG = 0xF0101C22.toInt()
        val BORDER = 0x667FD8EA
        val TEXT = 0xFFFFFFFF.toInt()
    }
}
