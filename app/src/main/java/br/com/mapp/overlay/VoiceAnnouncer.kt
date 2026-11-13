package br.com.mapp.overlay

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.ZoneHit
import br.com.mapp.core.model.ZoneType
import java.util.Locale

/** Aviso por voz: "Verde, 2,10 reais o quilômetro" + alertas de zona (R5). */
class VoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(appContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech indisponível ($status)")
            return
        }
        val lang = tts.setLanguage(PT_BR)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Voz pt-BR não instalada; usando idioma padrão do TTS")
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        ready = true
    }

    /** [delayMs] = espera antes de falar (som de corrida ótima tocando). */
    fun announce(offer: EvaluatedOffer, delayMs: Long = 0) {
        if (!ready || !OverlayPrefs.isVoiceEnabled(appContext)) return
        if (delayMs > 0) {
            tts.playSilentUtterance(delayMs, TextToSpeech.QUEUE_FLUSH, "$UTTERANCE_ID-pausa")
            tts.speak(phrase(offer), TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
        } else {
            tts.speak(phrase(offer), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    /** Alerta de zona que só ficou conhecido depois do 1º aviso (entra na fila, não corta a fala). */
    fun announceZones(hits: List<ZoneHit>) {
        if (hits.isEmpty() || !ready || !OverlayPrefs.isVoiceEnabled(appContext)) return
        val text = buildString { appendZones(hits) }.trim()
        if (text.isNotEmpty()) tts.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID + "-zona")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun phrase(offer: EvaluatedOffer): String = buildString {
        append(
            when (offer.signal) {
                Signal.GREEN -> "Verde"
                Signal.YELLOW -> "Amarelo"
                Signal.RED -> "Vermelho"
            },
        )
        val perKm = offer.metrics.perKm
        if (perKm != null) {
            append(". ").append(number(perKm)).append(" reais o quilômetro")
        } else {
            append(". Corrida de ").append(number(offer.raw.price)).append(" reais")
        }
        append('.')
        appendZones(offer.zoneHits)
    }

    private fun StringBuilder.appendZones(hits: List<ZoneHit>) {
        // Zonas perigosas primeiro.
        hits
            .filter { it.zone.alertEnabled }
            .sortedBy { if (it.zone.type == ZoneType.DANGER) 0 else 1 }
            .forEach { hit ->
                val onde = if (hit.role == PointRole.PICKUP) "Coleta" else "Destino"
                when (hit.zone.type) {
                    ZoneType.DANGER -> append(" Atenção! $onde em zona perigosa: ${hit.zone.name}.")
                    ZoneType.HIGH_DEMAND -> append(" $onde em alta demanda: ${hit.zone.name}.")
                    ZoneType.CUSTOM -> append(" $onde na zona ${hit.zone.name}.")
                }
            }
    }

    private fun number(v: Double): String = String.format(PT_BR, "%.2f", v)

    private companion object {
        const val TAG = "MApp/Voice"
        const val UTTERANCE_ID = "mapp-offer"
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    }
}
