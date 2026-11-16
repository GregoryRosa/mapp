package br.com.mapp.core

import br.com.mapp.core.card.CardLayout
import br.com.mapp.core.geo.SatelliteShot
import br.com.mapp.core.media.MediaArea
import br.com.mapp.core.media.MediaBlock
import br.com.mapp.core.media.MediaBlock.DROPOFF
import br.com.mapp.core.media.MediaBlock.MAP
import br.com.mapp.core.media.MediaBlock.PICKUP
import br.com.mapp.core.media.MediaLayout
import br.com.mapp.core.media.MediaPhotos
import br.com.mapp.core.media.MediaPlaced
import br.com.mapp.core.media.MediaSizes
import br.com.mapp.core.media.MediaTemplate
import br.com.mapp.core.notify.NoticeLayout
import br.com.mapp.core.route.RouteStop
import br.com.mapp.core.route.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLayoutTest {
    private fun leaf(b: MediaBlock) = MediaArea.Leaf(b)

    @Test fun padroes_iguais_ao_card_e_a_notificacao_de_antes() {
        // Card: mapa em cima, A e destino lado a lado embaixo.
        assertEquals(
            MediaArea.Group(false, listOf(leaf(MAP), MediaArea.Group(true, listOf(leaf(PICKUP), leaf(DROPOFF))))),
            MediaLayout.CARD_DEFAULT.arrange(),
        )
        // Notificação: mapa à esquerda, A em cima e destino embaixo à direita, 112 dp.
        val notice = MediaLayout.NOTICE_DEFAULT.arrange()!!
        assertEquals(MediaArea.Group(true, listOf(leaf(MAP), MediaArea.Group(false, listOf(leaf(PICKUP), leaf(DROPOFF))))), notice)
        assertEquals(112, MediaLayout.height(notice, MediaSizes.NOTICE))
        assertEquals(MediaLayout.CARD_DEFAULT, CardLayout.DEFAULT.media)
        assertEquals(MediaLayout.NOTICE_DEFAULT, NoticeLayout.DEFAULT.media)
    }

    @Test fun cada_modelo_tem_o_numero_de_espacos_pedido() {
        val slots = MediaTemplate.entries.associate { it to it.slots }
        assertEquals(1, slots[MediaTemplate.SINGLE])
        assertEquals(2, slots[MediaTemplate.COLUMNS])
        assertEquals(2, slots[MediaTemplate.ROWS])
        assertEquals(3, slots[MediaTemplate.COLUMNS_FIRST_SPLIT])
        assertEquals(3, slots[MediaTemplate.COLUMNS_SECOND_SPLIT])
        assertEquals(3, slots[MediaTemplate.THREE_ROWS])
    }

    @Test fun modelo_menor_deixa_os_ultimos_de_fora() {
        val only = MediaLayout(MediaTemplate.SINGLE, listOf(PICKUP, MAP, DROPOFF))
        assertEquals(listOf(PICKUP), only.shown)
        assertEquals(listOf(MAP, DROPOFF), only.hidden)
        assertEquals(leaf(PICKUP), only.arrange())
        assertNull(only.blockAt(1))
        assertNull(only.slotOf(MAP))
        // Volta para 3 espaços: a ordem continua a mesma.
        assertEquals(listOf(PICKUP, MAP, DROPOFF), only.withTemplate(MediaTemplate.THREE_ROWS).shown)
    }

    @Test fun arrastar_para_outro_espaco_troca_os_dois() {
        val l = MediaLayout(MediaTemplate.COLUMNS, listOf(MAP, PICKUP, DROPOFF))
        // Satélite/foto à esquerda e o mapa à direita.
        assertEquals(listOf(PICKUP, MAP, DROPOFF), l.put(PICKUP, 0).blocks)
        // Quadro de fora entra no lugar do mapa; o mapa sai.
        val out = l.put(DROPOFF, 0)
        assertEquals(listOf(DROPOFF, PICKUP), out.shown)
        assertEquals(listOf(MAP), out.hidden)
        // Mesmo lugar: nada muda; espaço que não existe: nada muda.
        assertEquals(l.blocks, l.put(MAP, 0).blocks)
        assertEquals(l.blocks, l.put(MAP, 2).blocks)
    }

    @Test fun quadro_que_nao_existe_some_e_o_vizinho_ocupa_o_espaco() {
        val l = MediaLayout.CARD_DEFAULT
        // Sem a chave do Google (sem fotos): só o mapa, como antes.
        assertEquals(leaf(MAP), l.arrange(setOf(MAP)))
        // Sem o destino: A ocupa a linha de baixo inteira.
        assertEquals(MediaArea.Group(false, listOf(leaf(MAP), leaf(PICKUP))), l.arrange(setOf(MAP, PICKUP)))
        // Sem nada: área some.
        assertNull(l.arrange(emptySet()))
        // Notificação com o mapa desligado: A e destino um embaixo do outro.
        assertEquals(
            MediaArea.Group(false, listOf(leaf(PICKUP), leaf(DROPOFF))),
            MediaLayout.NOTICE_DEFAULT.arrange(setOf(PICKUP, DROPOFF)),
        )
    }

    @Test fun texto_salvo_ida_e_volta() {
        val l = MediaLayout(MediaTemplate.COLUMNS_FIRST_SPLIT, listOf(DROPOFF, MAP, PICKUP))
        assertEquals("cols2a:dropoff,map,pickup", l.encode())
        assertEquals(l, MediaLayout.decode(l.encode(), MediaLayout.CARD_DEFAULT))
        // Estranho volta ao padrão; quadro faltando entra no fim.
        assertEquals(MediaLayout.CARD_DEFAULT, MediaLayout.decode("xyz:map", MediaLayout.CARD_DEFAULT))
        assertEquals(MediaLayout.NOTICE_DEFAULT, MediaLayout.decode(null, MediaLayout.NOTICE_DEFAULT))
        assertEquals(listOf(PICKUP, MAP, DROPOFF), MediaLayout.decode("rows3:pickup,pickup", MediaLayout.CARD_DEFAULT).blocks)
        // Dentro do Card e da notificação.
        val card = CardLayout.DEFAULT.copy(media = l)
        assertEquals(l.blocks, CardLayout.decode(card.encode()).media.blocks)
        assertEquals(MediaTemplate.COLUMNS_FIRST_SPLIT, CardLayout.decode(card.encode()).media.template)
        val notice = NoticeLayout.DEFAULT.copy(media = l)
        assertEquals(l.blocks, NoticeLayout.decode(notice.encode()).media.blocks)
        // Texto salvo antes desta versão (sem "media"): padrão de antes.
        assertEquals(MediaLayout.CARD_DEFAULT, CardLayout.decode("top=price;bottom=per_km").media)
    }

    @Test fun alturas_por_modelo() {
        val s = MediaSizes.CARD
        fun h(t: MediaTemplate) = MediaLayout.height(MediaLayout(t, listOf(MAP, PICKUP, DROPOFF)).arrange()!!, s)
        assertEquals(180, h(MediaTemplate.SINGLE))
        assertEquals(150, h(MediaTemplate.COLUMNS))
        assertEquals(200, h(MediaTemplate.COLUMNS_SECOND_SPLIT))
        assertEquals(160 + 6 + 160, h(MediaTemplate.ROWS))
        assertEquals(160 + 6 + 100, h(MediaTemplate.ROWS_SECOND_SPLIT))
        assertEquals(3 * 110 + 2 * 6, h(MediaTemplate.THREE_ROWS))
    }

    @Test fun retangulos_cobrem_a_area_sem_sobrepor() {
        val s = MediaSizes.CARD
        MediaTemplate.entries.forEach { t ->
            val area = MediaLayout(t, listOf(MAP, PICKUP, DROPOFF)).arrange()!!
            val w = 276
            val h = MediaLayout.height(area, s)
            val placed = MediaLayout.place(area, w, h, s)
            assertEquals(t.slots, placed.size)
            placed.forEach { p ->
                assertTrue("$t $p", p.x >= 0 && p.y >= 0 && p.x + p.width <= w && p.y + p.height <= h)
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertTrue("$t sobrepõe", !overlaps(placed[i], placed[j]))
            }
        }
    }

    @Test fun duas_linhas_seguem_a_proporcao_do_mapa_e_das_fotos() {
        val s = MediaSizes.CARD
        val area = MediaLayout.CARD_DEFAULT.arrange()!!
        val placed = MediaLayout.place(area, 276, 266, s).associateBy { it.block }
        assertEquals(MediaPlaced(MAP, 0, 0, 276, 160), placed[MAP])
        assertEquals(MediaPlaced(PICKUP, 0, 166, 135, 100), placed[PICKUP])
        assertEquals(MediaPlaced(DROPOFF, 141, 166, 135, 100), placed[DROPOFF])
    }

    @Test fun satelite_no_formato_do_quadro() {
        assertEquals(SatelliteShot.STRIP, SatelliteShot.forAspect(154.0 / 54)) // coluna da notificação
        assertEquals(SatelliteShot.CARD, SatelliteShot.forAspect(276.0 / 180)) // um só no Card
        assertEquals(SatelliteShot.SQUARE, SatelliteShot.forAspect(135.0 / 150)) // 2 colunas no Card
        assertEquals(SatelliteShot.TALL, SatelliteShot.forAspect(135.0 / 200))
        assertEquals(SatelliteShot.CARD, SatelliteShot.forAspect(0.0))
    }

    @Test fun fotos_sao_da_coleta_e_do_destino_mesmo_com_parada() {
        val route = listOf(
            RouteStop("A", StopKind.PICKUP, "Rua A", null),
            RouteStop("B", StopKind.STOP, "Rua B", null),
            RouteStop("C", StopKind.DROPOFF, "Rua C", null),
        )
        val stops = MediaPhotos.stops(route)
        assertEquals("A", stops[PICKUP]?.letter)
        assertEquals("C", stops[DROPOFF]?.letter)
        assertNull(stops[MAP])
    }

    private fun overlaps(a: MediaPlaced, b: MediaPlaced) =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}
