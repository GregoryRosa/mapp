package br.com.mapp.core

import br.com.mapp.core.card.AddressScope
import br.com.mapp.core.card.CardFilter
import br.com.mapp.core.card.CardFilters
import br.com.mapp.core.card.FilterField
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFilterTest {

    // R$ 29,20 · 27,8 km · 60 min → R$ 1,05/km (1,0504), R$ 29/h.
    private val raw = RawOffer(
        Platform.UBER, 29.20, tripKm = 22.0, tripMin = 45, pickupKm = 5.8, pickupMin = 15, passengerRating = 4.2,
        pickupAddress = "Av. Brasil, 4500 - Jd. Ângela", dropoffAddress = "Aeroporto de Congonhas, Vila Congonhas",
    )

    private fun eval(r: RawOffer = raw): EvaluatedOffer = OfferEvaluator.evaluate(r, FilterProfile(), VehicleCostConfig())

    private fun address(vararg words: String, scope: AddressScope = AddressScope.ANY, id: Long = 1) =
        CardFilter(id = id, item = FilterField.ADDRESS, words = words.toList(), scope = scope)

    private fun number(field: FilterField, min: Double? = null, max: Double? = null, id: Long = 2) =
        CardFilter(id = id, item = field, min = min, max = max)

    @Test
    fun `palavra inteira sem diferenciar maiuscula acento nem pontuacao`() {
        assertTrue(CardFilters.containsWord("Praça da Sé, 10", "se"))
        assertFalse(CardFilters.containsWord("Rua José Paulino, 50", "se"))
        assertTrue(CardFilters.containsWord("Av. Brasil, 4500 - Jd. Ângela", "JD ANGELA"))
        assertTrue(CardFilters.containsWord("Aeroporto de Congonhas", "aeroporto"))
        assertFalse(CardFilters.containsWord("Vila Nova Conceição", "vila conceição")) // precisam vir seguidas
        assertFalse(CardFilters.containsWord("Epicentro Comercial", "centro"))
        assertFalse(CardFilters.containsWord(null, "centro"))
        assertFalse(CardFilters.containsWord("Rua X", " , "))
    }

    @Test
    fun `endereco vale na coleta no destino e nas paradas conforme a escolha`() {
        val o = eval()
        assertTrue(CardFilters.matches(address("centro", "aeroporto"), o)) // basta uma das palavras
        assertFalse(CardFilters.matches(address("aeroporto", scope = AddressScope.PICKUP), o))
        assertTrue(CardFilters.matches(address("aeroporto", scope = AddressScope.DROPOFF), o))
        assertTrue(CardFilters.matches(address("brasil", scope = AddressScope.PICKUP), o))
        assertFalse(CardFilters.matches(address("brasil", scope = AddressScope.DROPOFF), o))

        val withStop = eval(raw.copy(stopAddresses = listOf("Rua do Mercado, 5"), stopCount = 1))
        assertTrue(CardFilters.matches(address("mercado"), withStop))
        assertFalse(CardFilters.matches(address("mercado", scope = AddressScope.DROPOFF), withStop))

        // Endereço ainda não lido: não casa (e não quebra).
        assertFalse(CardFilters.matches(address("brasil"), eval(raw.copy(pickupAddress = null, dropoffAddress = null))))
    }

    @Test
    fun `numero entre de e ate como o Card mostra`() {
        val o = eval()
        assertTrue(CardFilters.matches(number(FilterField.PER_KM, min = 1.05), o)) // 1,0504 aparece "1,05"
        assertFalse(CardFilters.matches(number(FilterField.PER_KM, min = 1.06), o))
        assertTrue(CardFilters.matches(number(FilterField.PER_KM, max = 1.05), o))
        assertTrue(CardFilters.matches(number(FilterField.PRICE, min = 20.0, max = 30.0), o))
        assertFalse(CardFilters.matches(number(FilterField.PRICE, min = 30.0), o))
        assertTrue(CardFilters.matches(number(FilterField.PER_HOUR, min = 29.0, max = 29.0), o))
        assertTrue(CardFilters.matches(number(FilterField.TOTAL_MIN, max = 60.0), o))
        assertFalse(CardFilters.matches(number(FilterField.TOTAL_MIN, max = 59.0), o))
        assertTrue(CardFilters.matches(number(FilterField.TOTAL_KM, min = 27.8, max = 27.8), o))
        assertTrue(CardFilters.matches(number(FilterField.PICKUP_KM, min = 5.0), o))
        assertTrue(CardFilters.matches(number(FilterField.PICKUP_MIN, max = 15.0), o))
        assertTrue(CardFilters.matches(number(FilterField.TRIP_KM, min = 22.0), o))
        assertTrue(CardFilters.matches(number(FilterField.TRIP_MIN, min = 45.0, max = 45.0), o))
        assertTrue(CardFilters.matches(number(FilterField.RATING, max = 4.5), o))
    }

    @Test
    fun `paradas e valores nao lidos`() {
        assertFalse(CardFilters.matches(number(FilterField.STOPS, min = 1.0), eval()))
        assertTrue(CardFilters.matches(number(FilterField.STOPS, min = 1.0), eval(raw.copy(stopCount = 1))))
        // Nota não lida: filtro de nota não casa, nem "até".
        assertFalse(CardFilters.matches(number(FilterField.RATING, max = 5.0), eval(raw.copy(passengerRating = null))))
        // Km da busca não lido: vem estimado pelo tempo (como na linha de endereços do Card).
        val noKm = eval(raw.copy(pickupKm = null))
        assertNotNull(CardFilters.value(FilterField.PICKUP_KM, noKm))
        assertNull(CardFilters.value(FilterField.ADDRESS, noKm))
    }

    @Test
    fun `filtro incompleto nunca casa`() {
        val o = eval()
        assertFalse(number(FilterField.PRICE).isComplete)
        assertFalse(CardFilters.matches(number(FilterField.PRICE), o))
        assertFalse(number(FilterField.PRICE, min = 30.0, max = 20.0).isComplete)
        assertFalse(address().isComplete)
        assertFalse(CardFilters.matches(address(), o))
        assertTrue(number(FilterField.PRICE, max = 20.0).isComplete)
        assertTrue(address("centro").isComplete)
    }

    @Test
    fun `ordem da lista decide a cor e desligado fica fora`() {
        val o = eval()
        val tag = address("aeroporto", id = 1).copy(color = 0xFF1565C0.toInt(), paint = false, name = "Aeroporto", speak = true)
        val paints = number(FilterField.PRICE, min = 20.0, id = 2).copy(color = 0xFFAD1457.toInt(), speak = true)
        val off = address("brasil", id = 3).copy(enabled = false)
        val miss = number(FilterField.PRICE, min = 100.0, id = 4)
        val matched = CardFilters.matching(listOf(tag, paints, off, miss), o)
        assertEquals(listOf(1L, 2L), matched.map { it.id })
        assertEquals(0xFFAD1457.toInt(), CardFilters.paintColor(matched)) // o 1º que pinta
        assertNull(CardFilters.paintColor(listOf(tag)))
        assertNull(CardFilters.paintColor(emptyList()))
        assertEquals(listOf("Aeroporto", "Valor da corrida a partir de 20,00"), CardFilters.spoken(matched))
        assertEquals(emptyList<String>(), CardFilters.spoken(listOf(tag.copy(speak = false))))
    }

    @Test
    fun `palavras digitadas viram lista limpa`() {
        assertEquals(
            listOf("Centro", "Vila Nova", "AEROPORTO", "sé"),
            CardFilters.parseWords("Centro, centro ;  Vila   Nova\nAEROPORTO,,| sé, ..."),
        )
        assertEquals(emptyList<String>(), CardFilters.parseWords("  ,; "))
        assertEquals(CardFilters.MAX_WORDS, CardFilters.parseWords((1..50).joinToString(",") { "rua$it" }).size)
    }

    @Test
    fun `resumo e nome do filtro`() {
        assertEquals("R$/Km a partir de 2,00", CardFilters.summary(number(FilterField.PER_KM, min = 2.0)))
        assertEquals("R$/Km de 2,00 até 3,00", CardFilters.summary(number(FilterField.PER_KM, min = 2.0, max = 3.0)))
        assertEquals("Busca (km) até 5,0", CardFilters.summary(number(FilterField.PICKUP_KM, max = 5.0)))
        assertEquals("Nota: sem valor", CardFilters.summary(number(FilterField.RATING)))
        assertEquals("Endereço: centro, vila nova", CardFilters.summary(address("centro", "vila nova")))
        assertEquals("Endereço · só destino: aeroporto", CardFilters.summary(address("aeroporto", scope = AddressScope.DROPOFF)))
        assertEquals("Endereço: sem palavras", CardFilters.summary(address()))

        assertEquals("centro, vila nova", address("centro", "vila nova").copy(name = "  ").displayName)
        assertEquals("Área de risco", address("centro").copy(name = " Área de risco ").displayName)
        assertEquals("Paradas no caminho a partir de 1", number(FilterField.STOPS, min = 1.0).displayName)
    }

    @Test
    fun `salvar e ler`() {
        val list = listOf(
            CardFilter(
                id = 10, name = "Área de risco", color = 0xFFC62828.toInt(), item = FilterField.ADDRESS,
                words = listOf("centro", "Vila Nova"), scope = AddressScope.DROPOFF, enabled = false, paint = true, speak = true,
            ),
            CardFilter(id = 11, name = "", color = 0xFFFFFFFF.toInt(), item = FilterField.PER_KM, min = 2.5, max = null, paint = false),
            CardFilter(id = 12, name = "Longa", color = 0xFF212121.toInt(), item = FilterField.TOTAL_KM, min = 20.0, max = 60.0),
        )
        assertEquals(list, CardFilters.decode(CardFilters.encode(list)))
        assertEquals(emptyList<CardFilter>(), CardFilters.decode(null))
        assertEquals(emptyList<CardFilter>(), CardFilters.decode("lixo"))
        assertEquals(emptyList<CardFilter>(), CardFilters.decode(CardFilters.encode(emptyList())))

        // Linha estranha (campo desconhecido) e id repetido saem; as outras ficam.
        val lines = CardFilters.encode(list).split('\n')
        val broken = listOf(lines[0], lines[1].replace("per_km", "nao_existe"), lines[2], lines[0], "v0\tx").joinToString("\n")
        assertEquals(listOf(10L, 12L), CardFilters.decode(broken).map { it.id })
    }

    @Test
    fun `nome e palavras nao quebram a gravacao`() {
        val f = CardFilter(id = 1, name = "Zona\tSul\nperigosa", item = FilterField.ADDRESS, words = listOf("a,b", "c\td", " "))
        val back = CardFilters.decode(CardFilters.encode(listOf(f))).single()
        assertEquals("Zona Sul perigosa", back.name)
        assertEquals(listOf("a", "b", "c", "d"), back.words)
    }

    @Test
    fun `criar salvar mover e apagar`() {
        val first = CardFilters.create(emptyList(), now = 1000)
        assertEquals(1000L, first.id)
        assertEquals(CardFilters.PALETTE[0], first.color)
        assertEquals(FilterField.ADDRESS, first.item)
        assertTrue(first.speak)

        val saved = CardFilters.upsert(emptyList(), first.copy(id = 2000, name = " Centro ", words = listOf("centro", "Centro")))
        assertEquals("Centro", saved.single().name)
        assertEquals(listOf("centro"), saved.single().words)

        val second = CardFilters.create(saved, now = 1000) // relógio atrasado: id continua único
        assertEquals(2001L, second.id)
        assertEquals(CardFilters.PALETTE[1], second.color)

        // Filtro de número não guarda palavras; de endereço não guarda limites.
        val numberFilter = second.copy(item = FilterField.PRICE, min = 30.0, words = listOf("x"))
        val both = CardFilters.upsert(saved, numberFilter)
        assertEquals(listOf(2000L, 2001L), both.map { it.id })
        assertEquals(emptyList<String>(), both[1].words)
        val edited = CardFilters.upsert(both, both[0].copy(min = 5.0, name = "Novo"))
        assertEquals("Novo", edited[0].name)
        assertNull(edited[0].min)
        assertEquals(2, edited.size)

        assertEquals(listOf(2001L, 2000L), CardFilters.move(both, 2001, -1).map { it.id })
        assertEquals(listOf(2001L, 2000L), CardFilters.move(both, 2000, 1).map { it.id })
        assertEquals(both, CardFilters.move(both, 2000, -1)) // já é o de cima
        assertEquals(both, CardFilters.move(both, 9, 1))
        assertEquals(listOf(2001L), CardFilters.remove(both, 2000).map { it.id })

        val full = (1..CardFilters.MAX_FILTERS).map { CardFilter(id = it.toLong(), words = listOf("a")) }
        assertEquals(CardFilters.MAX_FILTERS, CardFilters.upsert(full, CardFilter(id = 999, words = listOf("b"))).size)
    }

    @Test
    fun `texto escuro so em cor clara`() {
        assertTrue(CardFilters.darkText(0xFFFFD600.toInt()))
        assertTrue(CardFilters.darkText(0xFFFFFFFF.toInt()))
        assertFalse(CardFilters.darkText(0xFF1565C0.toInt()))
        assertFalse(CardFilters.darkText(0xFF212121.toInt()))
        assertFalse(CardFilters.darkText(0xFFC62828.toInt()))
        assertEquals(12, CardFilters.PALETTE.toSet().size)
    }
}
