package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import br.com.mapp.core.bubble.BubbleStyle
import br.com.mapp.core.card.CardFilter
import br.com.mapp.core.card.CardFilters
import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.geo.PhotoView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.geo.SatelliteZoom
import br.com.mapp.core.geo.StreetFraming
import br.com.mapp.core.geo.StreetTilt
import br.com.mapp.core.geo.StreetZoom
import br.com.mapp.core.nav.NavApp
import br.com.mapp.core.nav.NavHold
import br.com.mapp.core.notify.NoticeLayout
import br.com.mapp.core.sound.GreatSound
import br.com.mapp.core.sound.OfferSounds
import br.com.mapp.core.shift.OfflinePause

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
    const val KEY_CARD_FILTERS = "card_filters"
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
    const val KEY_STREET_ZOOM = "street_zoom"
    const val KEY_STREET_FRAMING = "street_framing"
    const val KEY_STREET_TILT = "street_tilt"
    const val KEY_SATELLITE_ZOOM = "satellite_zoom"
    const val KEY_TRIP_MEMORY = "trip_memory"
    const val KEY_NAV_HOLD_ON = "nav_hold_on"
    const val KEY_NAV_HOLD_MS = "nav_hold_ms"
    const val KEY_NAV_BRING_ON = "nav_bring_on"
    const val KEY_NAV_APP = "nav_app"
    const val KEY_AUTO_START_ON = "auto_start_on"
    const val KEY_AUTO_START_SNOOZED_AT = "auto_start_snoozed_at"
    const val KEY_UBER_EXTRA_ON = "uber_extra_on"
    const val KEY_OFFLINE_PAUSE_MIN = "offline_pause_min"
    const val KEY_OFFLINE_PAUSED_SHIFT = "offline_paused_shift"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Ligar o copiloto sozinho com a Uber aberta e online (tela inicial; padrão: ligado). */
    fun isAutoStartOn(context: Context): Boolean = of(context).getBoolean(KEY_AUTO_START_ON, true)

    /**
     * Ganho extra da Uber automático (Financeiro → Ganhos e gastos): o copiloto lança a diferença entre o
     * "Hoje" da tela inicial da Uber e as corridas contadas. Padrão ligado.
     */
    fun isUberExtraOn(context: Context): Boolean = of(context).getBoolean(KEY_UBER_EXTRA_ON, true)

    fun setUberExtraOn(context: Context, on: Boolean) {
        of(context).edit().putBoolean(KEY_UBER_EXTRA_ON, on).apply()
    }

    /**
     * Pausar a jornada depois de tantos minutos offline na Uber (tela inicial; `core/shift/OfflinePause`).
     * 0 = desligado; padrão 10 min.
     */
    fun offlinePauseMinutes(context: Context): Int =
        OfflinePause.sanitize(of(context).getInt(KEY_OFFLINE_PAUSE_MIN, OfflinePause.DEFAULT_MINUTES))

    fun setOfflinePauseMinutes(context: Context, minutes: Int) {
        of(context).edit().putInt(KEY_OFFLINE_PAUSE_MIN, OfflinePause.sanitize(minutes)).apply()
    }

    /** `startedAt` da jornada que a pausa por offline pausou (0 = nenhuma): ela volta sozinha ao ficar online. */
    fun offlinePausedShift(context: Context): Long = of(context).getLong(KEY_OFFLINE_PAUSED_SHIFT, 0L)

    fun setOfflinePausedShift(context: Context, startedAt: Long) {
        of(context).edit().putLong(KEY_OFFLINE_PAUSED_SHIFT, startedAt).apply()
    }

    fun setAutoStartOn(context: Context, on: Boolean) {
        of(context).edit().putBoolean(KEY_AUTO_START_ON, on).apply()
    }

    /** Quando o copiloto foi parado à mão (0 = sem pausa do automático — ver `core/copilot/AutoStart`). */
    fun autoStartSnoozedAt(context: Context): Long = of(context).getLong(KEY_AUTO_START_SNOOZED_AT, 0L)

    fun setAutoStartSnoozedAt(context: Context, at: Long) {
        of(context).edit().putLong(KEY_AUTO_START_SNOOZED_AT, at).apply()
    }

    /** Atraso da navegação (tela inicial → Segurar a navegação): padrão desligado. Ligar/desligar também liga o atalho (`NavLinkActivity.setEnabled`). */
    fun isNavHoldOn(context: Context): Boolean = of(context).getBoolean(KEY_NAV_HOLD_ON, false)

    fun setNavHoldOn(context: Context, on: Boolean) {
        of(context).edit().putBoolean(KEY_NAV_HOLD_ON, on).apply()
    }

    /**
     * Atraso da navegação com o motorista já no Waze/Google Maps: abrir a Uber para ler a corrida antes de
     * mandar a rota (padrão: ligado; só vale com o atraso ligado).
     */
    fun isNavBringOn(context: Context): Boolean = of(context).getBoolean(KEY_NAV_BRING_ON, true)

    fun setNavBringOn(context: Context, on: Boolean) {
        of(context).edit().putBoolean(KEY_NAV_BRING_ON, on).apply()
    }

    /** App que recebe a rota depois da espera (padrão: Waze). */
    fun navApp(context: Context): NavApp = NavApp.fromId(of(context).getString(KEY_NAV_APP, null))

    fun setNavApp(context: Context, app: NavApp) {
        of(context).edit().putString(KEY_NAV_APP, app.id).apply()
    }

    /** Espera máxima da navegação, em ms (um dos [NavHold.DELAYS_MS]). */
    fun navHoldMs(context: Context): Long = NavHold.validDelay(of(context).getLong(KEY_NAV_HOLD_MS, NavHold.DEFAULT_DELAY_MS))

    fun setNavHoldMs(context: Context, ms: Long) {
        of(context).edit().putLong(KEY_NAV_HOLD_MS, NavHold.validDelay(ms)).apply()
    }

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

    /** Filtros do Card (menu Card → Filtros): cor e etiqueta quando a corrida casa; ordem = prioridade da cor. */
    fun cardFilters(context: Context): List<CardFilter> = CardFilters.decode(of(context).getString(KEY_CARD_FILTERS, null))

    fun setCardFilters(context: Context, filters: List<CardFilter>) {
        of(context).edit().putString(KEY_CARD_FILTERS, CardFilters.encode(filters)).apply()
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

    /** Zoom do satélite (padrão 8× = zoom 20 no Card, ~90 m; 1× = como era antes). */
    fun satelliteZoom(context: Context): SatelliteZoom =
        SatelliteZoom.fromId(of(context).getString(KEY_SATELLITE_ZOOM, null))

    fun setSatelliteZoom(context: Context, zoom: SatelliteZoom) {
        of(context).edit().putString(KEY_SATELLITE_ZOOM, zoom.id).apply()
    }

    /** Zoom das fotos da rua (padrão 1× = campo de visão de antes). */
    fun streetZoom(context: Context): StreetZoom = StreetZoom.fromId(of(context).getString(KEY_STREET_ZOOM, null))

    fun setStreetZoom(context: Context, zoom: StreetZoom) {
        of(context).edit().putString(KEY_STREET_ZOOM, zoom.id).apply()
    }

    /** Enquadramento da foto da rua (padrão Rua: a câmera olha a via, não a fachada). */
    fun streetFraming(context: Context): StreetFraming =
        StreetFraming.fromId(of(context).getString(KEY_STREET_FRAMING, null))

    fun setStreetFraming(context: Context, framing: StreetFraming) {
        of(context).edit().putString(KEY_STREET_FRAMING, framing.id).apply()
    }

    /** Inclinação da câmera para baixo, em graus, nos enquadramentos Rua e Mista. */
    fun streetTilt(context: Context): Int =
        StreetTilt.snap(of(context).getInt(KEY_STREET_TILT, StreetTilt.DEFAULT))

    fun setStreetTilt(context: Context, deg: Int) {
        of(context).edit().putInt(KEY_STREET_TILT, StreetTilt.snap(deg)).apply()
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
