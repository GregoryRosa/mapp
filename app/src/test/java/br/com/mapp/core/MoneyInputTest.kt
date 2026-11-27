package br.com.mapp.core

import br.com.mapp.core.card.FilterField
import br.com.mapp.core.filter.FilterCriterion
import br.com.mapp.core.input.MoneyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyInputTest {

    /** Digita os números um a um, como no teclado, e devolve o que o campo mostra a cada tecla. */
    private fun type(keys: String): List<String> {
        var digits = ""
        return keys.map { k ->
            digits = MoneyInput.edit(digits, digits + k)
            MoneyInput.format(digits)
        }
    }

    @Test fun digita_da_direita_para_a_esquerda_em_centavos() {
        assertEquals(listOf("0,01", "0,17", "1,77", "17,73"), type("1773"))
        assertEquals("0,05", MoneyInput.format("5"))
        assertEquals("1.000,00", MoneyInput.format("100000"))
        assertEquals("1.234.567,89", MoneyInput.format("123456789"))
        assertEquals(17.73, MoneyInput.value("1773")!!, 1e-9)
        assertEquals(1000.0, MoneyInput.value("100000")!!, 1e-9)
    }

    @Test fun apagar_tira_o_ultimo_digito_e_vazio_continua_vazio() {
        assertEquals("177", MoneyInput.edit("1773", "177"))
        assertEquals("1,77", MoneyInput.format("177"))
        assertEquals("", MoneyInput.edit("5", ""))
        assertEquals("", MoneyInput.format(""))
        assertNull(MoneyInput.value(""))
        // Zeros à esquerda somem; só zero mostra 0,00.
        assertEquals("5", MoneyInput.edit("0", "05"))
        assertEquals("0,00", MoneyInput.format(MoneyInput.edit("", "0")))
    }

    @Test fun colar_texto_com_letras_fica_so_com_os_numeros() {
        assertEquals("1250", MoneyInput.edit("", "R$ 12,50"))
        assertEquals("12,50", MoneyInput.format(MoneyInput.edit("", "abc12x50")))
        assertEquals("", MoneyInput.edit("", "abc"))
    }

    @Test fun limite_de_digitos() {
        val max = "9".repeat(MoneyInput.MAX_DIGITS)
        assertEquals(max, MoneyInput.edit("", max))
        // Um dígito a mais é ignorado: o valor fica como estava.
        assertEquals(max, MoneyInput.edit(max, max + "1"))
        assertEquals("9.999.999,99", MoneyInput.format(max))
    }

    @Test fun valor_salvo_aparece_formatado_ao_abrir() {
        // O que as telas guardavam (Format.input) vira os dígitos do campo.
        assertEquals("1700", MoneyInput.digitsOf("17"))
        assertEquals("1750", MoneyInput.digitsOf("17,5"))
        assertEquals("123456", MoneyInput.digitsOf("1.234,56"))
        assertEquals("650", MoneyInput.digitsOf("6.5"))
        assertEquals("1773", MoneyInput.digitsOf(17.73))
        assertEquals("", MoneyInput.digitsOf(""))
        assertEquals("", MoneyInput.digitsOf(null as Double?))
        assertEquals("", MoneyInput.digitsOf(-3.0))
        // Ida e volta: o texto formatado lido de novo dá os mesmos dígitos.
        listOf("5", "1773", "100000", "123456789").forEach {
            assertEquals(it, MoneyInput.digitsOf(MoneyInput.format(it)))
        }
    }

    @Test fun so_campos_em_reais_viram_maquininha() {
        assertTrue(FilterCriterion.PER_KM.money)
        assertTrue(FilterCriterion.PROFIT.money)
        assertFalse(FilterCriterion.RATING.money)
        assertFalse(FilterCriterion.PICKUP_KM.money)
        assertTrue(FilterField.PRICE.isMoney)
        assertTrue(FilterField.NET_PER_HOUR.isMoney)
        assertFalse(FilterField.NET_PERCENT.isMoney)
        assertFalse(FilterField.TRIP_KM.isMoney)
    }
}
