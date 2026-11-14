package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import br.com.mapp.core.bubble.BubbleStyle
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.notify.NoticeLayout
import br.com.mapp.core.sound.GreatSound

/** Preferências do overlay (posição do Card, minimizado, voz, estilo do Card, som de corrida ótima, aparência do botão "M"). */
object OverlayPrefs {
    const val NAME = "mapp_overlay"
    const val KEY_VOICE_ENABLED = "voice_enabled"
    const val KEY_CARD_X = "card_x"
    const val KEY_CARD_Y = "card_y"
    const val KEY_CARD_MINIMIZED = "card_minimized"
    const val KEY_BUBBLE_X = "bubble_x"
    const val KEY_BUBBLE_Y = "bubble_y"
    const val KEY_CARD_LAYOUT = "card_layout"
    const val KEY_GREAT_SOUND = "great_sound"
    const val KEY_GREAT_SOUND_URI = "great_sound_uri"
    const val KEY_GOAL_MARK = "goal_mark"
    const val KEY_BUBBLE_STYLE = "bubble_style"
    const val KEY_NOTICE_LAYOUT = "notice_layout"

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

    /** Som de corrida ótima (tudo verde no Card); padrão: [GreatSound.DEFAULT]. */
    fun greatSound(context: Context): GreatSound = GreatSound.fromId(of(context).getString(KEY_GREAT_SOUND, null))

    fun setGreatSound(context: Context, sound: GreatSound) {
        of(context).edit().putString(KEY_GREAT_SOUND, sound.id).apply()
    }

    /** Som do celular escolhido para [GreatSound.PHONE] (null = padrão de notificação). */
    fun greatSoundUri(context: Context): Uri? = of(context).getString(KEY_GREAT_SOUND_URI, null)?.let(Uri::parse)

    fun setGreatSoundUri(context: Context, uri: Uri?) {
        of(context).edit().putString(KEY_GREAT_SOUND_URI, uri?.toString()).apply()
    }

    /** Última meta do dia comemorada com o som de dinheiro (`DayGoal.mark`). */
    fun goalMark(context: Context): String? = of(context).getString(KEY_GOAL_MARK, null)

    fun setGoalMark(context: Context, mark: String) {
        of(context).edit().putString(KEY_GOAL_MARK, mark).apply()
    }

    /** Aparência do botão "M" (cores, giro e som da meta), escolhida no painel ⚙ do próprio botão. */
    fun bubbleStyle(context: Context): BubbleStyle = BubbleStyle.decode(of(context).getString(KEY_BUBBLE_STYLE, null))

    fun setBubbleStyle(context: Context, style: BubbleStyle) {
        of(context).edit().putString(KEY_BUBBLE_STYLE, style.encode()).apply()
    }

    /** O que aparece na notificação da corrida aceita (menu Card → Notificação). */
    fun noticeLayout(context: Context): NoticeLayout = NoticeLayout.decode(of(context).getString(KEY_NOTICE_LAYOUT, null))

    fun setNoticeLayout(context: Context, layout: NoticeLayout) {
        of(context).edit().putString(KEY_NOTICE_LAYOUT, layout.encode()).apply()
    }
}
