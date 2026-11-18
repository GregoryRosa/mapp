package br.com.mapp.core

import br.com.mapp.core.bubble.SparkBurst
import br.com.mapp.core.bubble.SparkLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Faíscas do ícone "M" quando a corrida começa (o efeito da bolha estourando). */
class SparkBurstTest {

    @Test fun desligado_nao_faz_nada() {
        assertTrue(SparkBurst.sparks(SparkLevel.OFF).isEmpty())
        assertEquals(0L, SparkLevel.OFF.durationMs)
        assertNull(SparkBurst.state(SparkBurst.sparks(SparkLevel.DISCRETE).first(), SparkLevel.OFF, 0, 30f))
        assertEquals(0f, SparkBurst.ringAlpha(0, SparkLevel.OFF, 0), 0.001f)
    }

    @Test fun o_efeito_e_sempre_o_mesmo() {
        // Sorteio com semente fixa: o aviso não sai diferente a cada corrida.
        assertEquals(SparkBurst.sparks(SparkLevel.DISCRETE), SparkBurst.sparks(SparkLevel.DISCRETE))
        assertEquals(SparkLevel.DISCRETE.sparks, SparkBurst.sparks(SparkLevel.DISCRETE).size)
        assertEquals(SparkLevel.FIREWORKS.sparks, SparkBurst.sparks(SparkLevel.FIREWORKS).size)
        // Discreto é o padrão: menos faíscas, mais perto e mais rápido que os outros.
        assertEquals(SparkLevel.DISCRETE, SparkLevel.DEFAULT)
        assertTrue(SparkLevel.DISCRETE.sparks < SparkLevel.MEDIUM.sparks)
        assertTrue(SparkLevel.DISCRETE.reach < SparkLevel.MEDIUM.reach)
        assertTrue(SparkLevel.DISCRETE.durationMs < SparkLevel.MEDIUM.durationMs)
        SparkLevel.entries.forEach { assertEquals(it, SparkLevel.fromId(it.id)) }
        assertEquals(SparkLevel.DEFAULT, SparkLevel.fromId("lixo"))
    }

    @Test fun faisca_sai_do_botao_some_e_cabe_na_janela() {
        val level = SparkLevel.DISCRETE
        val radius = 30f
        val sparks = SparkBurst.sparks(level)
        // Espalhadas em volta (uma por setor): nenhum lado fica sem faísca.
        listOf(0f..90f, 90f..180f, 180f..270f, 270f..360f).forEach { quadrante ->
            assertTrue(sparks.any { it.angle in quadrante })
        }
        sparks.forEach { spark ->
            val inicio = SparkBurst.state(spark, level, 0, radius)!!
            assertEquals(0f, hypot(inicio.x, inicio.y), 0.001f) // sai do centro do botão
            var passos = 0
            for (ms in 0..level.durationMs step 20) {
                val s = SparkBurst.state(spark, level, ms, radius) ?: break
                val d = hypot(s.x, s.y)
                // Nunca sai da janela que o SparkOverlay abre em volta do botão.
                assertTrue("faísca passou da janela do efeito", d <= radius * (1f + level.reach))
                assertTrue(s.alpha in 0f..1f)
                assertTrue(s.width > 0f)
                passos++
            }
            // Vai para fora e vai apagando (no fim ela ainda cai um pouco, como num fogo).
            val vida = (level.durationMs * spark.life).toLong()
            val perto = SparkBurst.state(spark, level, vida / 10, radius)!!
            val longe = SparkBurst.state(spark, level, vida / 3, radius)!!
            assertTrue(hypot(longe.x, longe.y) > hypot(perto.x, perto.y))
            assertTrue(longe.alpha < perto.alpha)
            assertTrue("faísca precisa de vários quadros", passos > 5)
            // No fim do efeito já sumiu.
            assertNull(SparkBurst.state(spark, level, level.durationMs, radius))
        }
    }

    @Test fun anel_do_estouro_cresce_e_desaparece() {
        val level = SparkLevel.DISCRETE
        val radius = 30f
        // Começa na borda do círculo e cresce.
        assertEquals(radius * SparkBurst.RING_START, SparkBurst.ringRadius(0, level, 0, radius), 0.001f)
        assertTrue(SparkBurst.ringRadius(0, level, 200, radius) > SparkBurst.ringRadius(0, level, 50, radius))
        assertTrue(SparkBurst.ringAlpha(0, level, 50) > SparkBurst.ringAlpha(0, level, 200))
        // Acabou o efeito (e anel que o nível não tem): nada é desenhado.
        assertEquals(0f, SparkBurst.ringAlpha(0, level, level.durationMs), 0.001f)
        assertEquals(0f, SparkBurst.ringAlpha(1, level, 100), 0.001f)
        assertEquals(2, SparkLevel.FIREWORKS.rings)
        assertTrue(SparkBurst.ringAlpha(1, SparkLevel.FIREWORKS, 400) > 0f)
    }
}
