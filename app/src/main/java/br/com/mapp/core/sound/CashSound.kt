package br.com.mapp.core.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Som de dinheiro ("cha-ching" de caixa registradora) da meta do dia batida. Gerado no aparelho:
 * dois estalos metálicos (gaveta) e o sino agudo do caixa. Ruído com semente fixa = sempre igual.
 */
object CashSound {
    const val DURATION_MS = 950

    private const val FADE_MS = 15.0

    fun render(sampleRate: Int = SoundSynth.SAMPLE_RATE): ShortArray {
        val n = DURATION_MS * sampleRate / 1000
        val out = DoubleArray(n)
        val rnd = Lcg(0x2545F491L)
        // "Cha": gaveta abrindo — dois estalos de ruído agudo.
        click(out, sampleRate, startMs = 0, ms = 45, decayMs = 10.0, gain = 0.55, rnd = rnd)
        click(out, sampleRate, startMs = 75, ms = 70, decayMs = 16.0, gain = 0.8, rnd = rnd)
        // "Ching": sino do caixa (duas notas agudas juntas; harmônicos abaixo de metade da taxa).
        bell(out, sampleRate, startMs = 130, hz = 1975.5, decayMs = 360.0, gain = 1.0,
            partials = listOf(1.0 to 1.0, 2.0 to 0.3, 2.76 to 0.12))
        bell(out, sampleRate, startMs = 136, hz = 2637.0, decayMs = 320.0, gain = 0.7,
            partials = listOf(1.0 to 1.0, 2.0 to 0.25))

        // Termina em zero (sem estalo) e normaliza no mesmo volume dos outros sons.
        val fade = FADE_MS * sampleRate / 1000
        for (i in 0 until n) out[i] *= min(1.0, (n - 1 - i) / fade)
        out[0] = 0.0
        val max = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = SoundSynth.PEAK * Short.MAX_VALUE / max
        return ShortArray(n) { (out[it] * scale).roundToInt().toShort() }
    }

    private fun click(out: DoubleArray, rate: Int, startMs: Int, ms: Int, decayMs: Double, gain: Double, rnd: Lcg) {
        val start = startMs * rate / 1000
        val len = ms * rate / 1000
        var prev = 0.0
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i * 1000.0 / rate
            val x = rnd.next()
            // Diferença entre amostras = só o agudo do ruído (som metálico, sem "chiado" grave).
            val bright = (x - prev) / 2
            prev = x
            val env = min(1.0, i / (0.002 * rate)) * exp(-t / decayMs)
            out[idx] += gain * env * bright
        }
    }

    private fun bell(
        out: DoubleArray, rate: Int, startMs: Int, hz: Double, decayMs: Double, gain: Double,
        partials: List<Pair<Double, Double>>,
    ) {
        val start = startMs * rate / 1000
        val ampSum = partials.sumOf { it.second }
        val attack = 0.003 * rate
        for (idx in start until out.size) {
            val i = idx - start
            val t = i.toDouble() / rate
            val env = min(1.0, i / attack) * exp(-t * 1000 / decayMs)
            var v = 0.0
            partials.forEach { (ratio, amp) ->
                v += amp * sin(2 * PI * hz * ratio * t) * exp(-t * 1000 * (ratio - 1) / (decayMs * 2))
            }
            out[idx] += gain * env * v / ampSum
        }
    }

    /** Gerador simples e determinístico (−1..1). */
    private class Lcg(private var seed: Long) {
        fun next(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (seed ushr 33).toDouble() / (1L shl 30) - 1.0
        }
    }
}
