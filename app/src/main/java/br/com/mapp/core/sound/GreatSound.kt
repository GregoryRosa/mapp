package br.com.mapp.core.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Som de corrida ótima (tudo verde no Card), escolhido na tela do Card. Os sons do MApp são gerados
 * no aparelho ([SoundSynth]); [PHONE] usa um som do celular escolhido pelo motorista.
 */
enum class GreatSound(val id: String, val label: String) {
    OFF("off", "Sem som"),
    CHIME("chime", "Sino"),
    DING_DONG("dingdong", "Ding-dong"),
    RISE("rise", "Subida"),
    DOUBLE_BEEP("beep2", "Bip duplo"),
    PHONE("phone", "Som do celular");

    /** Gerado pelo próprio MApp (sem arquivo). */
    val synthesized: Boolean get() = this != OFF && this != PHONE

    companion object {
        val DEFAULT = CHIME

        fun fromId(id: String?): GreatSound = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Gera os sons em PCM 16 bits mono (Kotlin puro: o overlay só toca o que sai daqui). */
object SoundSynth {
    const val SAMPLE_RATE = 22_050

    /** Pico do volume (fração do máximo): alto o bastante no carro, sem distorcer. */
    const val PEAK = 0.6

    private const val ATTACK_MS = 6.0
    private const val RELEASE_MS = 12.0

    /**
     * Uma nota: [hz] por [ms], depois [gapMs] de silêncio. [decayMs] = quanto o som cai (sino);
     * [partials] = harmônicos (múltiplo da frequência → volume).
     */
    data class Note(
        val hz: Double,
        val ms: Int,
        val gapMs: Int = 0,
        val decayMs: Double = 1e9,
        val partials: List<Pair<Double, Double>> = PURE,
    )

    private val PURE = listOf(1.0 to 1.0)
    private val BELL = listOf(1.0 to 1.0, 2.0 to 0.35, 3.0 to 0.12, 4.2 to 0.06)
    private val WARM = listOf(1.0 to 1.0, 2.0 to 0.25)

    fun notes(sound: GreatSound): List<Note> = when (sound) {
        GreatSound.CHIME -> listOf(
            Note(880.0, 170, decayMs = 140.0, partials = BELL),
            Note(1318.5, 650, decayMs = 260.0, partials = BELL),
        )
        GreatSound.DING_DONG -> listOf(
            Note(659.25, 380, decayMs = 220.0, partials = BELL),
            Note(523.25, 620, decayMs = 300.0, partials = BELL),
        )
        GreatSound.RISE -> listOf(
            Note(523.25, 110, decayMs = 400.0, partials = WARM),
            Note(659.25, 110, decayMs = 400.0, partials = WARM),
            Note(783.99, 110, decayMs = 400.0, partials = WARM),
            Note(1046.5, 420, decayMs = 260.0, partials = WARM),
        )
        GreatSound.DOUBLE_BEEP -> listOf(
            Note(1200.0, 110, gapMs = 90),
            Note(1200.0, 150),
        )
        GreatSound.OFF, GreatSound.PHONE -> emptyList()
    }

    /** Duração total em ms (0 para [GreatSound.OFF]/[GreatSound.PHONE]). */
    fun durationMs(sound: GreatSound): Long = notes(sound).sumOf { (it.ms + it.gapMs).toLong() }

    fun render(sound: GreatSound, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val notes = notes(sound)
        if (notes.isEmpty()) return ShortArray(0)
        val out = ArrayList<Double>()
        notes.forEach { note -> appendNote(out, note, sampleRate) }
        val max = out.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = PEAK * Short.MAX_VALUE / max
        return ShortArray(out.size) { (out[it] * scale).roundToInt().toShort() }
    }

    private fun appendNote(out: MutableList<Double>, note: Note, sampleRate: Int) {
        val n = note.ms * sampleRate / 1000
        val attack = ATTACK_MS * sampleRate / 1000
        val release = RELEASE_MS * sampleRate / 1000
        val ampSum = note.partials.sumOf { it.second }
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            // Sobe rápido, cai como sino e termina em zero (sem estalo).
            val env = min(1.0, i / attack) * min(1.0, (n - 1 - i) / release) * exp(-t * 1000 / note.decayMs)
            var v = 0.0
            note.partials.forEach { (ratio, amp) ->
                // Harmônicos agudos somem antes, como num sino de verdade.
                v += amp * sin(2 * PI * note.hz * ratio * t) * exp(-t * 1000 * (ratio - 1) / (note.decayMs * 2))
            }
            out.add(env * v / ampSum)
        }
        repeat(note.gapMs * sampleRate / 1000) { out.add(0.0) }
    }
}
