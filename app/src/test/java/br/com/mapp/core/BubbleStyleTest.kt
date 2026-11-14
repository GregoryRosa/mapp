package br.com.mapp.core

import br.com.mapp.core.bubble.BubbleStyle
import br.com.mapp.core.bubble.ColorBar
import br.com.mapp.core.bubble.SpinSpeed
import br.com.mapp.core.sound.GoalSound
import br.com.mapp.core.sound.GreatSound
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleStyleTest {

    @Test fun codec_ida_e_volta() {
        val s = BubbleStyle(
            background = ColorBar.backgroundAt(0.5),
            logoStart = 0xFFFF0000.toInt(),
            logoEnd = 0xFF00FF00.toInt(),
            idleRing = 0xFFFFFFFF.toInt(),
            rideRing = 0xFF0000FF.toInt(),
            goalStart = 0xFFFFD600.toInt(),
            goalEnd = 0xFFFF1744.toInt(),
            spinSeconds = 3,
            goalSound = GoalSound.DING_DONG,
        )
        assertEquals(s, BubbleStyle.decode(s.encode()))
        assertEquals(BubbleStyle(), BubbleStyle.decode(BubbleStyle().encode()))
    }

    @Test fun texto_vazio_ou_errado_fica_no_padrao() {
        assertEquals(BubbleStyle(), BubbleStyle.decode(null))
        assertEquals(BubbleStyle(), BubbleStyle.decode("lixo"))
        // Campo ruim não derruba os outros.
        val s = BubbleStyle.decode("v1;bg=XYZ;m1=FFFF0000;spin=7;sound=nada")
        assertEquals(BubbleStyle.DEFAULT_BACKGROUND, s.background)
        assertEquals(0xFFFF0000.toInt(), s.logoStart)
        assertEquals(BubbleStyle.DEFAULT_SPIN_S, s.spinSeconds) // 7 não é um passo da barra
        assertEquals(GoalSound.DEFAULT, s.goalSound)
    }

    @Test fun padrao_e_o_visual_original() {
        val d = BubbleStyle()
        assertEquals(0xFF16404A.toInt(), d.background)
        assertEquals(0xFFFF9100.toInt(), d.idleRing)
        assertEquals(0xFF00E64D.toInt(), d.rideRing)
        assertEquals(12, d.spinSeconds)
        assertEquals(GoalSound.CASH, d.goalSound)
        assertArrayEquals(
            intArrayOf(BubbleStyle.TEAL, BubbleStyle.LILAC, BubbleStyle.PURPLE, BubbleStyle.LILAC, BubbleStyle.TEAL),
            d.goalColors(),
        )
        val custom = d.copy(goalStart = 0xFFFF0000.toInt(), goalEnd = 0xFF0000FF.toInt())
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt()), custom.goalColors())
    }

    @Test fun barra_de_cores_branco_e_arco_iris() {
        assertEquals(ColorBar.WHITE, ColorBar.colorAt(0.0))
        assertEquals(0xFFFF0000.toInt(), ColorBar.colorAt(ColorBar.WHITE_PART)) // matiz 0 = vermelho
        assertEquals(0xFFFF0000.toInt(), ColorBar.colorAt(1.0)) // volta ao vermelho
        val green = ColorBar.WHITE_PART + 120.0 / 360.0 * (1 - ColorBar.WHITE_PART)
        assertEquals(0xFF00FF00.toInt(), ColorBar.colorAt(green))
        val blue = ColorBar.WHITE_PART + 240.0 / 360.0 * (1 - ColorBar.WHITE_PART)
        assertEquals(0xFF0000FF.toInt(), ColorBar.colorAt(blue))
    }

    @Test fun posicao_na_barra_de_uma_cor() {
        // Cor viva: volta na mesma posição.
        for (f in listOf(0.1, 0.3, 0.55, 0.8, 0.95)) {
            assertEquals(f, ColorBar.fractionOf(ColorBar.colorAt(f)), 0.005)
        }
        // Branco e cinza ficam no pedaço branco.
        assertTrue(ColorBar.fractionOf(0xFFFFFFFF.toInt()) < ColorBar.WHITE_PART)
        assertTrue(ColorBar.fractionOf(0xFF808080.toInt()) < ColorBar.WHITE_PART)
        // Fundo escurecido mantém a matiz: o botão da barra fica no mesmo lugar.
        assertEquals(0.6, ColorBar.fractionOf(ColorBar.backgroundAt(0.6)), 0.01)
        // Fundo original (verde-água escuro) fica no verde-água da barra.
        val (h, _, _) = ColorBar.toHsv(BubbleStyle.DEFAULT_BACKGROUND)
        assertEquals(191.0, h, 2.0)
    }

    @Test fun fundo_e_escuro() {
        val (_, _, v) = ColorBar.toHsv(ColorBar.backgroundAt(0.5))
        assertEquals(ColorBar.BACKGROUND_LEVEL, v, 0.01)
    }

    @Test fun velocidade_do_giro_em_passos() {
        assertEquals(0, SpinSpeed.secondsAt(0.0))
        assertEquals(2, SpinSpeed.secondsAt(1.0))
        assertEquals(12, SpinSpeed.secondsAt(SpinSpeed.fractionOf(12)))
        SpinSpeed.STEPS.forEach { assertEquals(it, SpinSpeed.secondsAt(SpinSpeed.fractionOf(it))) }
        assertEquals("Parado", SpinSpeed.label(0))
        assertEquals("1 volta a cada 5 s", SpinSpeed.label(5))
    }

    @Test fun som_da_meta() {
        assertEquals(GoalSound.CASH, GoalSound.fromId(null))
        assertEquals(GreatSound.RISE, GoalSound.RISE.great)
        GoalSound.entries.forEach { assertEquals(it, GoalSound.fromId(it.id)) }
    }
}
