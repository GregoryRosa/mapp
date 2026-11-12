package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import br.com.mapp.core.card.CardLayout

/** Preferências do overlay (posição do cartão, minimizado, voz, estilo do cartão). */
object OverlayPrefs {
    const val NAME = "mapp_overlay"
    const val KEY_VOICE_ENABLED = "voice_enabled"
    const val KEY_CARD_X = "card_x"
    const val KEY_CARD_Y = "card_y"
    const val KEY_CARD_MINIMIZED = "card_minimized"
    const val KEY_BUBBLE_X = "bubble_x"
    const val KEY_BUBBLE_Y = "bubble_y"
    const val KEY_CARD_LAYOUT = "card_layout"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Aviso por voz (padrão: ligado). */
    fun isVoiceEnabled(context: Context): Boolean = of(context).getBoolean(KEY_VOICE_ENABLED, true)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    /** O que aparece no cabeçalho do cartão (tela "Estilo do cartão"). */
    fun cardLayout(context: Context): CardLayout = CardLayout.decode(of(context).getString(KEY_CARD_LAYOUT, null))

    fun setCardLayout(context: Context, layout: CardLayout) {
        of(context).edit().putString(KEY_CARD_LAYOUT, layout.encode()).apply()
    }
}
