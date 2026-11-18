package br.com.mapp.core.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Som do aviso de corrida iniciada (indo buscar o passageiro), escolhido nos ajustes do botão "M".
 * [POP] é a bolha estourando do [PopSound]; os outros reaproveitam os sons gerados do [SoundSynth].
 */
enum class RideSound(val id: String, val label: String, val great: GreatSound?) {
    POP("pop", "Bolha", null),
    CHIME("chime", "Sino", GreatSound.CHIME),
    DING_DONG("dingdong", "Ding-dong", GreatSound.DING_DONG),
    RISE("rise", "Subida", GreatSound.RISE),
    DOUBLE_BEEP("beep2", "Bip duplo", GreatSound.DOUBLE_BEEP),
    OFF("off", "Sem som", null);

    companion object {
        val DEFAULT = POP

        fun fromId(id: String?): RideSound = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Bolha estourando: estalo curto (o estouro) e um "plop" que sobe de tom logo depois, como a bolha
 * se fechando. Gerado no aparelho, curto de propósito — toca uma vez quando a corrida começa, sem
 * atrapalhar a Uber nem a voz. Ruído com semente fixa = sempre o mesmo som.
 */
object PopSound {
    const val DURATION_MS = 150

    private const val FADE_MS = 12.0

    /** Tom inicial e final do "plop" (sobe rápido, como numa bolha de água). */
    private const val HZ_START = 430.0
    private const val HZ_END = 1180.0

    fun render(sampleRate: Int = SoundSynth.SAMPLE_RATE): ShortArray {
        val n = DURATION_MS * sampleRate / 1000
        val out = DoubleArray(n)
        // Estouro: pancada curta de ruído agudo (a película da bolha arrebentando).
        burst(out, sampleRate, ms = 18, decayMs = 4.0, gain = 0.75)
        // "Plop": tom que sobe e morre em ~70 ms.
        chirp(out, sampleRate, startMs = 6, ms = 90, decayMs = 26.0, gain = 1.0)

        // Termina em zero (sem estalo) e no mesmo volume dos outros sons do MApp.
        val fade = FADE_MS * sampleRate / 1000
        for (i in 0 until n) out[i] *= min(1.0, (n - 1 - i) / fade)
        out[0] = 0.0
        val max = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = SoundSynth.PEAK * Short.MAX_VALUE / max
        return ShortArray(n) { (out[it] * scale).roundToInt().toShort() }
    }

    private fun burst(out: DoubleArray, rate: Int, ms: Int, decayMs: Double, gain: Double) {
        val len = ms * rate / 1000
        var prev = 0.0
        var seed = 0x5DEECE66DL
        for (i in 0 until min(len, out.size)) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val x = (seed ushr 33).toDouble() / (1L shl 30) - 1.0
            // Diferença entre amostras = só o agudo do ruído (estalo seco, sem chiado grave).
            val bright = (x - prev) / 2
            prev = x
            val t = i * 1000.0 / rate
            out[i] += gain * exp(-t / decayMs) * bright
        }
    }

    private fun chirp(out: DoubleArray, rate: Int, startMs: Int, ms: Int, decayMs: Double, gain: Double) {
        val start = startMs * rate / 1000
        val len = ms * rate / 1000
        val attack = 0.0015 * rate
        var phase = 0.0
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= out.size) break
            val k = i.toDouble() / len
            val hz = HZ_START + (HZ_END - HZ_START) * k * k
            phase += 2 * PI * hz / rate
            val t = i * 1000.0 / rate
            val env = min(1.0, i / attack) * exp(-t / decayMs)
            // Fundamental com um harmônico fraco: dá o corpo "molhado" do plop.
            out[idx] += gain * env * (sin(phase) + 0.2 * sin(2 * phase)) / 1.2
        }
    }
}
