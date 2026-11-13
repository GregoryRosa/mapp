package br.com.mapp.core

import br.com.mapp.core.filter.ThresholdRange
import br.com.mapp.core.filter.ThresholdScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ThresholdScaleTest {

    private val km = ThresholdScale.PER_KM

    @Test fun passo_e_casas() {
        assertEquals(2, km.decimals)
        assertEquals(0, ThresholdScale.PER_HOUR.decimals)
        assertEquals(1, ThresholdScale.PICKUP_KM.decimals)
        assertEquals(1.60, km.snap(1.61), 1e-9)
        assertEquals(1.65, km.snap(1.63), 1e-9)
        assertEquals(0.5, km.snap(-3.0), 1e-9)
        assertEquals(4.0, km.snap(9.0), 1e-9)
    }

    @Test fun posicao_e_valor_vao_e_voltam() {
        // Como no print: R$ 1,60 a ~31% e R$ 2,00 a ~43% de uma escala 0,50–4,00.
        assertEquals(0.314, km.fraction(1.60), 1e-3)
        assertEquals(1.60, km.valueAt(km.fraction(1.60)), 1e-9)
        assertEquals(2.00, km.valueAt(km.fraction(2.00)), 1e-9)
        assertEquals(1.0, km.fraction(7.0), 1e-9)
        assertEquals(0.0, km.fraction(0.1), 1e-9)
        assertEquals(4.71, ThresholdScale.RATING.valueAt(0.71), 1e-9)
    }

    @Test fun valor_digitado_fora_estica_a_escala() {
        val wide = km.fitting(1.6, 5.37)
        assertEquals(0.5, wide.min, 1e-9)
        assertEquals(5.40, wide.max, 1e-9)
        assertEquals(1.0, wide.fraction(5.40), 1e-9)
        val low = ThresholdScale.RATING.fitting(3.55)
        assertEquals(3.55, low.min, 1e-9)
        assertEquals(5.0, low.max, 1e-9)
        assertEquals(0.0, ThresholdScale.MIN_PRICE.fitting(-2.0).min, 1e-9)
        assertSame(km, km.fitting(1.6, 2.0, null))
    }

    @Test fun amarelo_nunca_passa_do_verde() {
        val r = ThresholdRange(1.6, 2.0)
        assertEquals(ThresholdRange(2.3, 2.3), r.withLow(2.3))
        assertEquals(ThresholdRange(1.2, 1.2), r.withHigh(1.2))
        assertEquals(ThresholdRange(1.8, 2.0), r.withLow(1.8))
    }

    @Test fun faixa_do_perfil_mantem_o_semaforo() {
        assertNull(ThresholdRange.of(null, null))
        assertEquals(ThresholdRange(1.5, 1.5), ThresholdRange.of(1.5, null))
        assertEquals(ThresholdRange(0.0, 2.0), ThresholdRange.of(null, 2.0))
        assertEquals(ThresholdRange(2.0, 2.0), ThresholdRange.of(2.5, 2.0))
        assertEquals(ThresholdRange(1.5, 2.0), ThresholdRange.of(1.5, 2.0))
    }
}
