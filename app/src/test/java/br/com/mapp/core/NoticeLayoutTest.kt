package br.com.mapp.core

import br.com.mapp.core.notify.NoticeItem
import br.com.mapp.core.notify.NoticeLayout
import br.com.mapp.core.notify.NoticeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeLayoutTest {
    private val d = NoticeLayout.DEFAULT

    @Test fun padrao_igual_a_notificacao_de_antes() {
        assertEquals(listOf(NoticeItem.KIND, NoticeItem.PRICE), d.line(NoticeLine.TITLE))
        assertEquals(listOf(NoticeItem.PICKUP, NoticeItem.TRIP), d.line(NoticeLine.TEXT))
        assertTrue(d.headline)
        assertTrue(d.showMap)
        assertEquals(listOf(NoticeItem.NET_PER_HOUR), d.available)
    }

    @Test fun colocar_em_espaco_vazio_vai_para_o_fim() {
        val l = d.place(NoticeItem.NET_PER_HOUR, NoticeLine.TITLE, 2)
        assertEquals(listOf(NoticeItem.KIND, NoticeItem.PRICE, NoticeItem.NET_PER_HOUR), l.line(NoticeLine.TITLE))
        assertTrue(l.available.isEmpty())
        // Linha cheia: disponível não entra.
        val cheia = l.remove(NoticeItem.ZONES)
        assertEquals(cheia, cheia.place(NoticeItem.ZONES, NoticeLine.TITLE, 3))
    }

    @Test fun mover_entre_linhas_e_trocar_de_lugar() {
        // De uma linha para espaço vazio de outra.
        val a = d.place(NoticeItem.RATING, NoticeLine.EXTRA4, 1)
        assertEquals(listOf(NoticeItem.ZONES, NoticeItem.PASSENGER), a.line(NoticeLine.EXTRA3))
        assertEquals(listOf(NoticeItem.PICKUP_ADDRESS, NoticeItem.RATING), a.line(NoticeLine.EXTRA4))
        // Para espaço ocupado: os dois trocam.
        val b = d.place(NoticeItem.PER_KM, NoticeLine.TITLE, 1)
        assertEquals(listOf(NoticeItem.KIND, NoticeItem.PER_KM), b.line(NoticeLine.TITLE))
        assertEquals(listOf(NoticeItem.PRICE, NoticeItem.PER_HOUR, NoticeItem.NET), b.line(NoticeLine.EXTRA1))
        // Disponível em espaço ocupado: o ocupante volta para os disponíveis.
        val c = d.place(NoticeItem.NET_PER_HOUR, NoticeLine.TITLE, 0)
        assertEquals(listOf(NoticeItem.NET_PER_HOUR, NoticeItem.PRICE), c.line(NoticeLine.TITLE))
        assertEquals(listOf(NoticeItem.KIND), c.available)
    }

    @Test fun tirar_item() {
        val l = d.remove(NoticeItem.PRICE)
        assertEquals(listOf(NoticeItem.KIND), l.line(NoticeLine.TITLE))
        assertTrue(NoticeItem.PRICE in l.available)
    }

    @Test fun codec_ida_e_volta() {
        val l = d.place(NoticeItem.NET_PER_HOUR, NoticeLine.EXTRA5, 1).copy(headline = false, showMap = false)
        assertEquals(l, NoticeLayout.decode(l.encode()))
        assertEquals(d, NoticeLayout.decode(d.encode()))
    }

    @Test fun texto_estranho_volta_ao_padrao_e_nao_repete_item() {
        assertEquals(d, NoticeLayout.decode(null))
        assertEquals(d, NoticeLayout.decode("lixo"))
        val l = NoticeLayout.decode("v1;title=price,price,kind,nada,per_km,per_hour;text=price,trip;map=0")
        assertEquals(listOf(NoticeItem.PRICE, NoticeItem.KIND, NoticeItem.PER_KM), l.line(NoticeLine.TITLE))
        assertEquals(listOf(NoticeItem.TRIP), l.line(NoticeLine.TEXT)) // valor já usado no título
        assertTrue(l.headline)
        assertFalse(l.showMap)
    }
}
