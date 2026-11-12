package br.com.mapp.reader

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.reader.OfferTracker.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras de "qual oferta está na tela" com as duas fontes (texto e imagem). */
class OfferTrackerTest {
    private val published = ArrayList<RawOffer>()
    private var clears = 0

    private fun tracker(windowSupported: Boolean = true) =
        OfferTracker(windowSupported, publish = { published += it }, clear = { clears++ })

    private val offer = RawOffer(
        platform = Platform.UBER, price = 24.54, tripKm = 14.8, tripMin = 29, pickupKm = 1.6, pickupMin = 6,
        pickupAddress = "Av. Sen. Salgado Filho", dropoffAddress = "R. Dona Laura, 320", capturedAt = 1L,
    )
    private val other = offer.copy(price = 14.41, tripKm = 6.4, tripMin = 16, pickupKm = 3.9, pickupMin = 9)

    private val gone: (RawOffer) -> Boolean = { false }
    private val covered: (RawOffer) -> Boolean = { true }

    /** Um ciclo de captura em que cada modo devolve [byMode] (null = nenhuma oferta no quadro). */
    private fun OfferTracker.cycle(now: Long, still: (RawOffer) -> Boolean = gone, byMode: (Mode) -> RawOffer?) {
        for (mode in beginCycle(backupOnly = false)) onImage(mode, byMode(mode), now, still)
    }

    @Test fun imagem_publica_uma_vez_e_fica_com_a_captura_por_janela() {
        val t = tracker()
        assertEquals(listOf(Mode.WINDOW, Mode.DISPLAY), t.modes())

        // 1º ciclo: a janela lê a oferta; na tela o preço está coberto pelo cartão do MApp.
        t.cycle(1_000, covered) { if (it == Mode.WINDOW) offer else null }
        assertEquals(listOf(offer), published)
        assertTrue(t.shownByImage)

        // Quadros seguintes: mesma oferta relida, preço lido errado num quadro → nada de republicar.
        t.cycle(2_800) { if (it == Mode.WINDOW) offer.copy(capturedAt = 2L, rawText = "x") else offer.copy(price = 85.20) }
        t.cycle(4_600, covered) { null }
        assertEquals(1, published.size)
        assertEquals(0, clears)

        // O cartão sumiu: só depois de dois ciclos seguidos sem ver.
        t.cycle(6_400) { null }
        assertEquals(0, clears)
        t.cycle(8_200) { null }
        assertEquals(1, clears)
        assertNull(t.shown)

        // A janela enxergou: daqui em diante só ela, e a oferta some em 2 quadros.
        assertEquals(Mode.WINDOW, t.lockedMode)
        assertEquals(listOf(Mode.WINDOW), t.modes())
        t.cycle(10_000) { other }
        assertEquals(listOf(offer, other), published)
        t.cycle(11_200) { null }
        assertEquals(1, clears)
        t.cycle(12_400) { null }
        assertEquals(2, clears)
    }

    @Test fun se_so_a_tela_inteira_enxerga_fica_com_ela() {
        val t = tracker()
        t.cycle(1_000) { if (it == Mode.DISPLAY) offer else null }
        assertEquals(1, published.size)
        // A janela nunca vê; a tela continua vendo: a oferta não pode piscar.
        repeat(5) { i -> t.cycle(2_000L + i * 1_800) { if (it == Mode.DISPLAY) offer else null } }
        assertEquals(1, published.size)
        assertEquals(0, clears)
        t.cycle(20_000) { null }
        t.cycle(21_800) { null }
        assertEquals(1, clears)
        assertEquals(Mode.DISPLAY, t.lockedMode)
        assertEquals(listOf(Mode.DISPLAY), t.modes())
    }

    @Test fun quadro_mais_completo_atualiza_o_cartao() {
        val t = tracker()
        val semDestino = offer.copy(dropoffAddress = null)
        t.cycle(1_000) { if (it == Mode.WINDOW) semDestino else null }
        t.cycle(2_800) { if (it == Mode.WINDOW) offer else null }
        assertEquals(listOf(semDestino, offer), published)
        // Menos completo, ou mais completo mas com outro preço (leitura duvidosa): fica como está.
        t.cycle(4_600) { if (it == Mode.WINDOW) semDestino else null }
        t.cycle(6_400) { if (it == Mode.WINDOW) offer.copy(price = 1.50) else null }
        assertEquals(2, published.size)
    }

    @Test fun oferta_nova_substitui_a_anterior_sem_limpar() {
        val t = tracker()
        t.cycle(1_000) { offer }
        t.cycle(2_800) { other }
        assertEquals(listOf(offer, other), published)
        assertEquals(0, clears)
        assertEquals(other, t.shown)
    }

    @Test fun app_de_corrida_saiu_da_tela_copiloto_parou_ou_captura_travou() {
        val t = tracker(windowSupported = false)
        assertEquals(listOf(Mode.DISPLAY), t.modes())

        t.cycle(1_000) { offer }
        t.onNoTarget()
        assertEquals(0, clears)
        t.onNoTarget()
        assertEquals(1, clears)

        t.cycle(5_000) { offer }
        t.onPaused()
        assertEquals(2, clears)

        t.cycle(9_000) { offer }
        t.onTick(9_000 + OfferTracker.IMAGE_STALE_MS)
        assertEquals(2, clears)
        t.onTick(9_001 + OfferTracker.IMAGE_STALE_MS)
        assertEquals(3, clears)

        // Sem oferta na tela nada disso faz efeito.
        t.onNoTarget(); t.onPaused(); t.onTick(99_999); t.reset()
        assertEquals(3, clears)
    }

    @Test fun texto_completo_manda_e_a_imagem_vira_reserva() {
        val t = tracker()
        assertFalse(t.isTreeProven(Platform.UBER))
        assertEquals(OfferTracker.TREE_CLEAR_AFTER_MS, t.onTree(offer, 0))
        assertEquals(listOf(offer), published)
        assertTrue(t.treeHasIt)
        assertTrue(t.isTreeProven(Platform.UBER))
        assertEquals(listOf(Mode.WINDOW), t.beginCycle(backupOnly = true))

        // A imagem vê a mesma oferta (ou nada): quem manda é o texto.
        t.onImage(Mode.WINDOW, offer.copy(dropoffAddress = "R.DonaLaura,320"), 200, gone)
        t.onImage(Mode.WINDOW, null, 400, gone)
        t.onImage(Mode.WINDOW, null, 600, gone)
        assertEquals(1, published.size)
        assertEquals(0, clears)

        // No texto qualquer mudança republica (leitura exata); repetição não.
        t.onTree(offer.copy(capturedAt = 9L), 700)
        assertEquals(1, published.size)
        t.onTree(offer.copy(dropoffAddress = "R. Dona Laura, 320 - Rio Branco"), 800)
        assertEquals(2, published.size)

        // Sumiu do texto: espera 1,5 s antes de limpar.
        assertEquals(1_000L, t.onTree(null, 1_300))
        assertEquals(0, clears)
        assertNull(t.onTree(null, 2_300))
        assertEquals(1, clears)
        assertNull(t.onTree(null, 2_400))
    }

    @Test fun texto_incompleto_cede_para_a_imagem() {
        val t = tracker()
        // O texto só pegou um pedaço (ex.: tempo do mapa + ganhos): oferta sem o trecho de busca.
        val pedaco = RawOffer(Platform.UBER, price = 85.20, tripKm = 5.3, tripMin = 12, capturedAt = 1L)
        t.onTree(pedaco, 0)
        assertFalse(t.treeHasIt)
        assertFalse(t.isTreeProven(Platform.UBER))

        t.cycle(500) { if (it == Mode.WINDOW) offer else null }
        assertEquals(listOf(pedaco, offer), published)
        assertEquals(OfferTracker.Source.IMAGE, t.source)

        // Com a imagem no comando, o texto não troca nem limpa a oferta.
        assertNull(t.onTree(pedaco, 600))
        assertNull(t.onTree(null, 5_000))
        assertEquals(2, published.size)
        assertEquals(0, clears)

        t.cycle(6_000) { null }
        t.cycle(7_800) { null }
        assertEquals(1, clears)
    }

    @Test fun falhas_na_captura_por_janela_caem_para_a_tela_inteira() {
        val t = tracker()
        t.onImageError(Mode.WINDOW)
        t.onImageError(Mode.WINDOW)
        t.onImage(Mode.WINDOW, null, 0, gone) // funcionou: zera a contagem
        t.onImageError(Mode.WINDOW)
        t.onImageError(Mode.DISPLAY) // erro na tela não desliga nada
        assertEquals(listOf(Mode.WINDOW, Mode.DISPLAY), t.modes())
        repeat(OfferTracker.MAX_WINDOW_FAILURES) { t.onImageError(Mode.WINDOW) }
        assertEquals(listOf(Mode.DISPLAY), t.modes())

        // Já travado na janela e ela para de funcionar: destrava e volta para a tela.
        val locked = tracker()
        locked.cycle(1_000) { if (it == Mode.WINDOW) offer else null }
        locked.cycle(2_800) { null }
        locked.cycle(4_600) { null }
        assertEquals(Mode.WINDOW, locked.lockedMode)
        repeat(OfferTracker.MAX_WINDOW_FAILURES) { locked.onImageError(Mode.WINDOW) }
        assertNull(locked.lockedMode)
        assertEquals(listOf(Mode.DISPLAY), locked.modes())
    }
}
