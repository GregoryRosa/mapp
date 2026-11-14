package br.com.mapp.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.com.mapp.core.sound.CashSound
import br.com.mapp.core.sound.GoalSound
import br.com.mapp.core.sound.GreatSound
import br.com.mapp.core.sound.SoundSynth

/**
 * Toca o som de corrida ótima (tudo verde no Card). Mesmo volume da voz do MApp (áudio de
 * navegação). Usado pelo copiloto e pelo botão de teste na tela do Card.
 */
class GreatSoundPlayer(context: Context) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var ringtone: Ringtone? = null

    /**
     * Toca o som escolhido (padrão: o salvo na tela do Card). Devolve quanto tempo dura, para a
     * voz esperar o som acabar.
     */
    fun play(
        sound: GreatSound = OverlayPrefs.greatSound(app),
        phoneUri: Uri? = OverlayPrefs.greatSoundUri(app),
    ): Long {
        stop()
        return when {
            sound == GreatSound.OFF -> 0L
            sound == GreatSound.PHONE -> playPhone(phoneUri)
            else -> playSynth(sound)
        }
    }

    /** Som de dinheiro da meta do dia batida. */
    fun playCash(): Long {
        stop()
        return playPcm(CashSound.render(), CashSound.DURATION_MS.toLong())
    }

    /** Som da meta do dia batida escolhido nos ajustes do botão "M" (padrão: dinheiro). */
    fun playGoal(sound: GoalSound = OverlayPrefs.bubbleStyle(app).goalSound): Long {
        val synth = sound.great
        return when {
            sound == GoalSound.CASH -> playCash()
            synth != null -> { stop(); playSynth(synth) }
            else -> { stop(); 0L }
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        track?.let(::release)
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
    }

    private fun playSynth(sound: GreatSound): Long =
        playPcm(SoundSynth.render(sound), SoundSynth.durationMs(sound))

    private fun playPcm(pcm: ShortArray, ms: Long): Long {
        if (pcm.isEmpty()) return 0L
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SoundSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
            handler.postDelayed({ release(t) }, ms + 300)
            ms
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao tocar o som", e)
            0L
        }
    }

    private fun playPhone(uri: Uri?): Long {
        val u = uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return 0L
        return try {
            val r = RingtoneManager.getRingtone(app, u) ?: return 0L
            r.audioAttributes = ATTRIBUTES
            r.play()
            ringtone = r
            // Som do celular pode ser longo (toque de chamada): corta para não cobrir a voz.
            handler.postDelayed({
                runCatching { r.stop() }
                if (ringtone === r) ringtone = null
            }, PHONE_MAX_MS)
            PHONE_VOICE_DELAY_MS
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao tocar o som do celular", e)
            0L
        }
    }

    private fun release(t: AudioTrack) {
        runCatching { t.stop() }
        runCatching { t.release() }
        if (track === t) track = null
    }

    companion object {
        private const val TAG = "MApp/GreatSound"
        private const val PHONE_MAX_MS = 3_000L
        private const val PHONE_VOICE_DELAY_MS = 1_500L

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Nome do som do celular escolhido ("Som do celular" se não der para ler). */
        fun phoneSoundTitle(context: Context, uri: Uri?): String {
            val u = uri ?: return "Padrão de notificação"
            return runCatching { RingtoneManager.getRingtone(context, u)?.getTitle(context) }.getOrNull()
                ?: GreatSound.PHONE.label
        }
    }
}
