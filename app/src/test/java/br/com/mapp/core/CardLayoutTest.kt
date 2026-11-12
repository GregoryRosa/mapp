package br.com.mapp.core

import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.card.CardLine
import br.com.mapp.core.card.CardMetric.NET_PERCENT
import br.com.mapp.core.card.CardMetric.NET_PROFIT
import br.com.mapp.core.card.CardMetric.PER_HOUR
import br.com.mapp.core.card.CardMetric.PER_KM
import br.com.mapp.core.card.CardMetric.PER_MIN
import br.com.mapp.core.card.CardMetric.PRICE
import br.com.mapp.core.card.CardMetric.RATING
import br.com.mapp.core.card.CardMetric.TOTAL_TIME
import br.com.mapp.core.card.CardValues
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.VehicleCostConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardLayoutTest {

    private val def = CardLayout.DEFAULT

    @Test
    fun `padrao igual ao Rota PRO`() {
        assertEquals(listOf(PRICE, TOTAL_TIME, RATING), def.top)
        assertEquals(listOf(PER_KM, PER_HOUR, NET_PROFIT), def.bottom)
        assertEquals(false, PER_MIN in def.top + def.bottom)
        assertEquals(true, PER_MIN in def.available)
    }

    @Test
    fun `disponivel vai para espaco vazio no fim da linha`() {
        val l = def.place(PER_MIN, CardLine.BOTTOM, 3)
        assertEquals(listOf(PER_KM, PER_HOUR, NET_PROFIT, PER_MIN), l.bottom)
        // Linha cheia: não entra em espaço vazio.
        assertEquals(l, l.place(NET_PERCENT, CardLine.BOTTOM, 4))
    }

    @Test
    fun `disponivel em espaco ocupado substitui e devolve o antigo`() {
        val l = def.place(PER_MIN, CardLine.TOP, 1)
        assertEquals(listOf(PRICE, PER_MIN, RATING), l.top)
        assertEquals(true, TOTAL_TIME in l.available)
    }

    @Test
    fun `mover entre linhas troca de lugar`() {
        val l = def.place(PER_KM, CardLine.TOP, 0)
        assertEquals(listOf(PER_KM, TOTAL_TIME, RATING), l.top)
        assertEquals(listOf(PRICE, PER_HOUR, NET_PROFIT), l.bottom)
    }

    @Test
    fun `reordenar na mesma linha`() {
        assertEquals(listOf(NET_PROFIT, PER_HOUR, PER_KM), def.place(PER_KM, CardLine.BOTTOM, 2).bottom)
        // Para um espaço vazio da mesma linha: vai para o fim.
        assertEquals(listOf(PER_HOUR, NET_PROFIT, PER_KM), def.place(PER_KM, CardLine.BOTTOM, 3).bottom)
    }

    @Test
    fun `mover para espaco vazio de outra linha`() {
        val l = def.remove(RATING).place(PER_HOUR, CardLine.TOP, 2)
        assertEquals(listOf(PRICE, TOTAL_TIME, PER_HOUR), l.top)
        assertEquals(listOf(PER_KM, NET_PROFIT), l.bottom)
    }

    @Test
    fun `salvar e ler`() {
        val l = def.place(PER_MIN, CardLine.BOTTOM, 3).remove(RATING).copy(showPlatform = false)
        assertEquals(l, CardLayout.decode(l.encode()))
        assertEquals(def, CardLayout.decode(null))
        assertEquals(def, CardLayout.decode("lixo"))
        // Item repetido ou desconhecido é ignorado.
        val odd = CardLayout.decode("top=price,price,xyz;bottom=price,per_km;platform=1")
        assertEquals(listOf(PRICE), odd.top)
        assertEquals(listOf(PER_KM), odd.bottom)
    }

    @Test
    fun `valores e cores`() {
        val raw = RawOffer(Platform.UBER, 29.20, tripKm = 22.0, tripMin = 45, pickupKm = 5.8, pickupMin = 15, passengerRating = 4.2)
        val profile = FilterProfile(minPerKm = 1.5, goodPerKm = 2.0, minPerHour = 30.0, goodPerHour = 45.0, maxPickupKm = 4.0)
        val o = OfferEvaluator.evaluate(raw, profile, VehicleCostConfig())
        assertEquals("R$ 29,20", CardValues.full(PRICE, o))
        assertEquals("1h00m", CardValues.full(TOTAL_TIME, o))
        assertEquals("★ 4,20", CardValues.full(RATING, o))
        assertEquals("1,05", CardValues.compact(PER_KM, o))
        assertEquals("29", CardValues.compact(PER_HOUR, o))
        assertEquals(Signal.RED, CardValues.tone(PER_KM, o, profile))
        assertEquals(Signal.RED, CardValues.tone(PER_HOUR, o, profile))
        assertNull(CardValues.tone(RATING, o, profile)) // sem nota mínima no perfil
        assertEquals("9 min", CardValues.duration(9))
    }
}
