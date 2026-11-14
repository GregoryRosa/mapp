package br.com.mapp.core.bubble

import br.com.mapp.core.sound.GoalSound
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aparência do botão flutuante "M", escolhida no painel ⚙ do próprio botão. Cores em ARGB.
 * - [background]: brilho do fundo do círculo (o resto do fundo é preto);
 * - [logoStart]/[logoEnd]: gradiente do "M" (anel da partida / ponto da chegada);
 * - anel de dentro (status): [idleRing] = ligado sem corrida, [rideRing] = em corrida
 *   (leitor desligado continua cinza com "!");
 * - anel de fora (meta do dia): gradiente [goalStart] → [goalEnd]; [spinSeconds] = uma volta a cada
 *   N segundos com a meta batida (0 = parado);
 * - [goalSound]: som da meta batida.
 * O padrão é o visual original do MApp.
 */
data class BubbleStyle(
    val background: Int = DEFAULT_BACKGROUND,
    val logoStart: Int = TEAL,
    val logoEnd: Int = LILAC,
    val idleRing: Int = ORANGE,
    val rideRing: Int = GREEN,
    val goalStart: Int = TEAL,
    val goalEnd: Int = LILAC,
    val spinSeconds: Int = DEFAULT_SPIN_S,
    val goalSound: GoalSound = GoalSound.DEFAULT,
) {
    /**
     * Cores do gradiente do anel da meta, em volta do círculo (a partir do topo). Com as cores
     * padrão é o gradiente original da marca (verde-água → lilás → roxo → lilás → verde-água).
     */
    fun goalColors(): IntArray =
        if (goalStart == TEAL && goalEnd == LILAC) intArrayOf(TEAL, LILAC, PURPLE, LILAC, TEAL)
        else intArrayOf(goalStart, goalEnd, goalStart)

    fun encode(): String = listOf(
        "v1",
        "bg=" + hex(background),
        "m1=" + hex(logoStart),
        "m2=" + hex(logoEnd),
        "idle=" + hex(idleRing),
        "ride=" + hex(rideRing),
        "g1=" + hex(goalStart),
        "g2=" + hex(goalEnd),
        "spin=$spinSeconds",
        "sound=" + goalSound.id,
    ).joinToString(";")

    companion object {
        val TEAL: Int = 0xFF5EE6D8.toInt()
        val LILAC: Int = 0xFFB59CFF.toInt()
        val PURPLE: Int = 0xFF8F6BFF.toInt()
        val ORANGE: Int = 0xFFFF9100.toInt()
        val GREEN: Int = 0xFF00E64D.toInt()
        /** Verde-água escuro do fundo original. */
        val DEFAULT_BACKGROUND: Int = 0xFF16404A.toInt()
        const val DEFAULT_SPIN_S = 12

        /** Lê o que [encode] gravou; o que faltar ou estiver errado fica no padrão. */
        fun decode(text: String?): BubbleStyle {
            val d = BubbleStyle()
            if (text.isNullOrBlank()) return d
            val parts = text.split(';')
            if (parts.firstOrNull() != "v1") return d
            val map = parts.drop(1).mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
            }.toMap()
            fun color(key: String, def: Int): Int = map[key]?.let(::parseHex) ?: def
            return BubbleStyle(
                background = color("bg", d.background),
                logoStart = color("m1", d.logoStart),
                logoEnd = color("m2", d.logoEnd),
                idleRing = color("idle", d.idleRing),
                rideRing = color("ride", d.rideRing),
                goalStart = color("g1", d.goalStart),
                goalEnd = color("g2", d.goalEnd),
                spinSeconds = map["spin"]?.toIntOrNull()?.takeIf { it in SpinSpeed.STEPS } ?: d.spinSeconds,
                goalSound = map["sound"]?.let { GoalSound.fromId(it) } ?: d.goalSound,
            )
        }

        private fun hex(c: Int): String = "%08X".format(c)

        private fun parseHex(s: String): Int? =
            s.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()
    }
}

/**
 * Barra de cores dos ajustes do botão: o começo é branco e o resto é o arco-íris (matiz 0–360°,
 * cor viva). [colorAt] dá a cor de uma posição 0..1; [fractionOf] devolve onde uma cor fica na barra
 * (cor sem saturação = branco; as outras pela matiz).
 */
object ColorBar {
    /** Pedaço branco no começo da barra. */
    const val WHITE_PART = 0.08

    val WHITE: Int = 0xFFFFFFFF.toInt()

    /** Fundo do botão: a cor da barra escurecida (o fundo é escuro para o "M" aparecer). */
    const val BACKGROUND_LEVEL = 0.3

    fun colorAt(fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        if (f < WHITE_PART) return WHITE
        val hue = (f - WHITE_PART) / (1 - WHITE_PART) * 360.0
        return hsv(hue, 1.0, 1.0)
    }

    fun fractionOf(color: Int): Double {
        val (h, s, v) = toHsv(color)
        if (s < 0.15 || v < 0.05) return WHITE_PART / 2
        return WHITE_PART + h / 360.0 * (1 - WHITE_PART)
    }

    /** Matiz (0–360), saturação e brilho (0–1). */
    fun toHsv(color: Int): Triple<Double, Double, Double> {
        val r = (color shr 16 and 0xFF) / 255.0
        val g = (color shr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60.0 * (((g - b) / d).mod(6.0))
            mx == g -> 60.0 * ((b - r) / d + 2)
            else -> 60.0 * ((r - g) / d + 4)
        }
        val s = if (mx == 0.0) 0.0 else d / mx
        return Triple(h, s, mx)
    }

    fun hsv(hue: Double, saturation: Double, value: Double): Int {
        val h = hue.mod(360.0)
        val c = value * saturation
        val x = c * (1 - abs((h / 60.0).mod(2.0) - 1))
        val m = value - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Mesma cor com [level] do brilho (0..1); mantém a matiz. */
    fun darken(color: Int, level: Double): Int {
        fun ch(shift: Int) = ((color shr shift and 0xFF) * level).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Cor do fundo do botão para uma posição da barra. */
    fun backgroundAt(fraction: Double): Int = darken(colorAt(fraction), BACKGROUND_LEVEL)
}

/** Velocidade do giro do anel da meta batida: passos de "parado" a "rápido". */
object SpinSpeed {
    /** Segundos por volta, do mais lento ao mais rápido; 0 = parado. */
    val STEPS: List<Int> = listOf(0, 30, 20, 12, 8, 5, 3, 2)

    /** Passo mais perto da posição 0..1 da barra. */
    fun secondsAt(fraction: Double): Int {
        val i = (fraction.coerceIn(0.0, 1.0) * (STEPS.size - 1)).roundToInt()
        return STEPS[i]
    }

    fun fractionOf(seconds: Int): Double {
        val i = STEPS.indexOf(seconds).takeIf { it >= 0 } ?: STEPS.indexOf(BubbleStyle.DEFAULT_SPIN_S)
        return i.toDouble() / (STEPS.size - 1)
    }

    fun label(seconds: Int): String = if (seconds <= 0) "Parado" else "1 volta a cada $seconds s"
}
