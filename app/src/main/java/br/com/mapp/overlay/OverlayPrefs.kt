package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import br.com.mapp.core.bubble.BubbleStyle
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.geo.PhotoView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.notify.NoticeLayout
import br.com.mapp.core.sound.GreatSound
import br.com.mapp.core.sound.OfferSounds

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
    const val KEY_STOP_SOUND = "stop_sound"
    const val KEY_STOP_SOUND_URI = "stop_sound_uri"
    const val KEY_GOAL_MARK = "goal_mark"
    const val KEY_BUBBLE_STYLE = "bubble_style"
    const val KEY_NOTICE_LAYOUT = "notice_layout"
    const val KEY_STREETVIEW_ON = "streetview_on"
    const val KEY_STREETVIEW_KEY = "streetview_key"
    const val KEY_STREETVIEW_BUDGET = "streetview_budget"
    const val KEY_SATELLITE_BUDGET = "satellite_budget"
    const val KEY_PHOTO_VIEW = "photo_view"
    const val KEY_TRIP_MEMORY = "trip_memory"

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

    /**
     * Aviso de corrida com parada no caminho ("1 parada" no cartão); padrão: [OfferSounds.STOP_DEFAULT].
     * [GreatSound.OFF] = aviso desligado.
     */
    fun stopSound(context: Context): GreatSound =
        of(context).getString(KEY_STOP_SOUND, null)?.let { GreatSound.fromId(it) } ?: OfferSounds.STOP_DEFAULT

    fun setStopSound(context: Context, sound: GreatSound) {
        of(context).edit().putString(KEY_STOP_SOUND, sound.id).apply()
    }

    /** Som do celular escolhido para o aviso de parada (null = padrão de notificação). */
    fun stopSoundUri(context: Context): Uri? = of(context).getString(KEY_STOP_SOUND_URI, null)?.let(Uri::parse)

    fun setStopSoundUri(context: Context, uri: Uri?) {
        of(context).edit().putString(KEY_STOP_SOUND_URI, uri?.toString()).apply()
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

    /**
     * Chave do Google colada pelo usuário (fica só no celular, fora do APK): Street View Static API
     * para as fotos da rua e Maps Static API para o satélite.
     */
    fun streetViewKey(context: Context): String? =
        of(context).getString(KEY_STREETVIEW_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setStreetViewKey(context: Context, key: String?) {
        of(context).edit().putString(KEY_STREETVIEW_KEY, key?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    /** Fotos da rua no Card (padrão: ligado; só aparecem com a chave colada). */
    fun isStreetViewOn(context: Context): Boolean = of(context).getBoolean(KEY_STREETVIEW_ON, true)

    fun setStreetViewOn(context: Context, on: Boolean) {
        of(context).edit().putBoolean(KEY_STREETVIEW_ON, on).apply()
    }

    fun streetViewEnabled(context: Context): Boolean = isStreetViewOn(context) && streetViewKey(context) != null

    /** Fotos pedidas ao Google no mês. */
    fun streetViewBudget(context: Context): StreetViewBudget =
        StreetViewBudget.decode(of(context).getString(KEY_STREETVIEW_BUDGET, null))

    fun setStreetViewBudget(context: Context, budget: StreetViewBudget) {
        of(context).edit().putString(KEY_STREETVIEW_BUDGET, budget.encode()).apply()
    }

    /** Imagens de satélite (Maps Static API) pedidas ao Google no mês — contador separado das fotos da rua. */
    fun satelliteBudget(context: Context): StreetViewBudget =
        StreetViewBudget.decode(of(context).getString(KEY_SATELLITE_BUDGET, null))

    fun setSatelliteBudget(context: Context, budget: StreetViewBudget) {
        of(context).edit().putString(KEY_SATELLITE_BUDGET, budget.encode()).apply()
    }

    /**
     * Fotos do Card: foto da rua ou satélite. Toque numa foto do Card troca (e fica salvo para as
     * próximas corridas); também dá para escolher em Card → Card de corridas.
     */
    fun photoView(context: Context): PhotoView = PhotoView.fromId(of(context).getString(KEY_PHOTO_VIEW, null))

    fun setPhotoView(context: Context, view: PhotoView) {
        of(context).edit().putString(KEY_PHOTO_VIEW, view.id).apply()
    }

    /** Memória do aceite automático (`TripMatcher.snapshot`): ofertas recentes e corridas abertas. */
    fun tripMemory(context: Context): String? = of(context).getString(KEY_TRIP_MEMORY, null)

    fun setTripMemory(context: Context, text: String?) {
        of(context).edit().putString(KEY_TRIP_MEMORY, text).apply()
    }
}
