package br.com.mapp.core

import br.com.mapp.core.bubble.BubbleSize
import br.com.mapp.core.bubble.BubbleStyle
import br.com.mapp.core.bubble.ColorBar
import br.com.mapp.core.bubble.SpinSpeed
import br.com.mapp.core.bubble.SparkLevel
import br.com.mapp.core.sound.GoalSound
import br.com.mapp.core.sound.GreatSound
import br.com.mapp.core.sound.RideSound
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
            sparkLevel = SparkLevel.FIREWORKS,
            rideSound = RideSound.RISE,
            iconSize = 130,
            textSize = 160,
            pinSize = 140,
            arrowSize = 130,
            arrowColor = 0xFFFF9100.toInt(),
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

    @Test fun tamanho_do_botao_e_do_texto() {
        val d = BubbleStyle()
        assertEquals(100, d.iconSize)
        assertEquals(100, d.textSize)
        assertEquals(60, BubbleSize.iconDp(100)) // um pouco maior que os 52 dp de antes
        assertEquals(42, BubbleSize.iconDp(70))
        assertEquals(96, BubbleSize.iconDp(160))
        assertEquals(1.5f, BubbleSize.textScale(150), 0.001f)
        // Barras em passos de 10%: ida e volta, pontas e padrão.
        BubbleSize.ICON_STEPS.forEach { assertEquals(it, BubbleSize.percentAt(BubbleSize.ICON_STEPS, BubbleSize.fractionOf(BubbleSize.ICON_STEPS, it))) }
        BubbleSize.TEXT_STEPS.forEach { assertEquals(it, BubbleSize.percentAt(BubbleSize.TEXT_STEPS, BubbleSize.fractionOf(BubbleSize.TEXT_STEPS, it))) }
        assertEquals(70, BubbleSize.percentAt(BubbleSize.ICON_STEPS, 0.0))
        assertEquals(160, BubbleSize.percentAt(BubbleSize.ICON_STEPS, 1.0))
        assertEquals(200, BubbleSize.percentAt(BubbleSize.TEXT_STEPS, 1.0))
        assertEquals("120%", BubbleSize.label(120))
        // Pin: 60% a 160%, 100% = padrão.
        assertEquals(100, d.pinSize)
        assertEquals(60, BubbleSize.percentAt(BubbleSize.PIN_STEPS, 0.0))
        assertEquals(160, BubbleSize.percentAt(BubbleSize.PIN_STEPS, 1.0))
        BubbleSize.PIN_STEPS.forEach { assertEquals(it, BubbleSize.percentAt(BubbleSize.PIN_STEPS, BubbleSize.fractionOf(BubbleSize.PIN_STEPS, it))) }
        assertEquals(0.6f, BubbleSize.pinScale(60), 0.001f)
        assertEquals(100, BubbleStyle.decode("v1;pin=55").pinSize)
        // Seta: 60% a 160%; sem cor gravada acompanha o gradiente do "M" (null).
        assertEquals(100, d.arrowSize)
        assertEquals(null, d.arrowColor)
        assertEquals(null, BubbleStyle.decode(d.encode()).arrowColor)
        assertEquals(160, BubbleSize.percentAt(BubbleSize.ARROW_STEPS, 1.0))
        BubbleSize.ARROW_STEPS.forEach { assertEquals(it, BubbleSize.percentAt(BubbleSize.ARROW_STEPS, BubbleSize.fractionOf(BubbleSize.ARROW_STEPS, it))) }
        assertEquals(1.3f, BubbleSize.arrowScale(130), 0.001f)
        assertEquals(100, BubbleStyle.decode("v1;arrow=300").arrowSize)
        assertEquals(0xFF00E64D.toInt(), BubbleStyle.decode("v1;ac=FF00E64D").arrowColor)
        assertEquals(null, BubbleStyle.decode("v1;ac=xyz").arrowColor)
        // Configuração antiga (sem tamanho) ou valor fora dos passos fica em 100%.
        assertEquals(100, BubbleStyle.decode("v1;spin=8").iconSize)
        val bad = BubbleStyle.decode("v1;size=135;text=500")
        assertEquals(100, bad.iconSize)
        assertEquals(100, bad.textSize)
    }

    @Test fun som_da_meta() {
        assertEquals(GoalSound.CASH, GoalSound.fromId(null))
        assertEquals(GreatSound.RISE, GoalSound.RISE.great)
        GoalSound.entries.forEach { assertEquals(it, GoalSound.fromId(it.id)) }
    }

    @Test fun aviso_de_corrida_iniciada() {
        // Padrão: faíscas discretas e a bolha estourando.
        assertEquals(SparkLevel.DISCRETE, BubbleStyle().sparkLevel)
        assertEquals(RideSound.POP, BubbleStyle().rideSound)
        SparkLevel.entries.forEach { assertEquals(it, SparkLevel.fromId(it.id)) }
        RideSound.entries.forEach { assertEquals(it, RideSound.fromId(it.id)) }
        // Configuração antiga (sem o aviso) e id errado ficam no padrão.
        assertEquals(SparkLevel.DISCRETE, BubbleStyle.decode("v1;spin=8").sparkLevel)
        assertEquals(RideSound.POP, BubbleStyle.decode("v1;rsound=lixo").rideSound)
        assertEquals(SparkLevel.OFF, BubbleStyle.decode("v1;spark=off").sparkLevel)
        assertEquals(GreatSound.CHIME, RideSound.CHIME.great)
        assertEquals(null, RideSound.POP.great)
    }
}
