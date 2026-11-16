package br.com.mapp.core.media

import br.com.mapp.core.route.RouteStop
import br.com.mapp.core.route.StopKind
import kotlin.math.roundToInt

/**
 * Quadros da área de mapa e fotos do Card e da notificação da corrida aceita: o mini mapa com a rota
 * e as fotos (rua ou satélite) da coleta e do destino.
 */
enum class MediaBlock(val id: String, val label: String, val short: String) {
    MAP("map", "Mapa da rota", "Mapa"),
    PICKUP("pickup", "Foto da coleta (A)", "Coleta A"),
    DROPOFF("dropoff", "Foto do destino", "Destino");

    companion object {
        fun fromId(id: String): MediaBlock? = entries.firstOrNull { it.id == id.trim() }
    }
}

/** Divisão da área em espaços: folha = espaço `n`; grupo = lado a lado ([Split.columns]) ou um embaixo do outro. */
sealed interface MediaNode {
    data class Slot(val index: Int) : MediaNode
    data class Split(val columns: Boolean, val children: List<MediaNode>) : MediaNode
}

/** Área já com os quadros nos lugares (só os que existem nesta corrida). */
sealed interface MediaArea {
    data class Leaf(val block: MediaBlock) : MediaArea
    data class Group(val columns: Boolean, val children: List<MediaArea>) : MediaArea
}

/** Um quadro desenhado: posição e tamanho dentro da área (mesma unidade do tamanho pedido). */
data class MediaPlaced(val block: MediaBlock, val x: Int, val y: Int, val width: Int, val height: Int)

/** Modelos prontos da área de mapa e fotos (menu Card → Card de corridas / Notificação). */
enum class MediaTemplate(val id: String, val label: String, val tree: MediaNode) {
    SINGLE("one", "Um só", MediaNode.Slot(0)),
    COLUMNS("cols2", "2 colunas", cols(MediaNode.Slot(0), MediaNode.Slot(1))),
    COLUMNS_FIRST_SPLIT("cols2a", "2 colunas, a 1ª em 2 linhas", cols(rows(MediaNode.Slot(0), MediaNode.Slot(1)), MediaNode.Slot(2))),
    COLUMNS_SECOND_SPLIT("cols2b", "2 colunas, a 2ª em 2 linhas", cols(MediaNode.Slot(0), rows(MediaNode.Slot(1), MediaNode.Slot(2)))),
    ROWS("rows2", "2 linhas", rows(MediaNode.Slot(0), MediaNode.Slot(1))),
    ROWS_FIRST_SPLIT("rows2a", "2 linhas, a 1ª em 2 colunas", rows(cols(MediaNode.Slot(0), MediaNode.Slot(1)), MediaNode.Slot(2))),
    ROWS_SECOND_SPLIT("rows2b", "2 linhas, a 2ª em 2 colunas", rows(MediaNode.Slot(0), cols(MediaNode.Slot(1), MediaNode.Slot(2)))),
    THREE_ROWS("rows3", "3 linhas", rows(MediaNode.Slot(0), MediaNode.Slot(1), MediaNode.Slot(2)));

    /** Quantos quadros cabem neste modelo. */
    val slots: Int get() = count(tree)

    companion object {
        fun fromId(id: String?): MediaTemplate? = entries.firstOrNull { it.id == id?.trim() }

        private fun count(n: MediaNode): Int = when (n) {
            is MediaNode.Slot -> 1
            is MediaNode.Split -> n.children.sumOf { count(it) }
        }
    }
}

private fun cols(vararg c: MediaNode) = MediaNode.Split(columns = true, children = c.toList())
private fun rows(vararg c: MediaNode) = MediaNode.Split(columns = false, children = c.toList())

/**
 * Alturas da área de cada superfície (dp no Card, dp na notificação): [single] = um quadro só;
 * [columns] = quadros lado a lado; [splitColumns] = colunas com uma delas dividida em 2 linhas;
 * [row2]/[row3] = cada linha inteira de um quadro quando há 2 / 3 linhas; [rowGroup] = linha com
 * 2 quadros lado a lado dentro de 2 linhas; [gap] = espaço entre os quadros.
 */
data class MediaSizes(
    val single: Int,
    val columns: Int,
    val splitColumns: Int,
    val row2: Int,
    val row3: Int,
    val rowGroup: Int,
    val gap: Int,
) {
    companion object {
        /** Card da oferta (área de 276 dp de largura). Padrão: mapa 160 + fotos 100 (antes: 180 + 96). */
        val CARD = MediaSizes(single = 180, columns = 150, splitColumns = 200, row2 = 160, row3 = 110, rowGroup = 100, gap = 6)

        /**
         * Notificação aberta e painel Atividades (~310 dp de largura). Padrão: 112 dp como antes; em
         * linhas a área cresce e sobra menos espaço para as linhas de texto da notificação.
         */
        val NOTICE = MediaSizes(single = 112, columns = 112, splitColumns = 112, row2 = 72, row3 = 58, rowGroup = 64, gap = 4)
    }
}

/**
 * Onde fica cada quadro: [template] escolhido e [order] = os 3 quadros na ordem dos espaços (o 1º vai
 * no espaço 1…). Modelo com menos espaços deixa os últimos de fora ([hidden]). Arrastar um quadro para
 * outro espaço troca os dois de lugar ([put]); trocar de modelo mantém a ordem.
 */
data class MediaLayout(
    val template: MediaTemplate,
    val order: List<MediaBlock>,
) {
    /** Os 3 quadros, sem repetir, sempre todos (os que faltam entram no fim). */
    val blocks: List<MediaBlock> = normalize(order)

    /** Quadros que aparecem, na ordem dos espaços. */
    val shown: List<MediaBlock> get() = blocks.take(template.slots)

    /** Quadros que ficam de fora com este modelo. */
    val hidden: List<MediaBlock> get() = blocks.drop(template.slots)

    /** Quadro do espaço [slot] (null = espaço que o modelo não tem). */
    fun blockAt(slot: Int): MediaBlock? = if (slot in 0 until template.slots) blocks[slot] else null

    /** Espaço do quadro (null = fora do modelo). */
    fun slotOf(block: MediaBlock): Int? = blocks.indexOf(block).takeIf { it in 0 until template.slots }

    fun withTemplate(t: MediaTemplate): MediaLayout = copy(template = t, order = blocks)

    /** Troca dois quadros de lugar (também entre um espaço e os de fora). */
    fun swap(a: MediaBlock, b: MediaBlock): MediaLayout {
        if (a == b) return copy(order = blocks)
        val list = blocks.toMutableList()
        val i = list.indexOf(a)
        val j = list.indexOf(b)
        list[i] = b
        list[j] = a
        return copy(order = list)
    }

    /** Põe [block] no espaço [slot]; quem estava lá vai para o lugar de onde [block] saiu. */
    fun put(block: MediaBlock, slot: Int): MediaLayout {
        val occupant = blockAt(slot) ?: return copy(order = blocks)
        return swap(block, occupant)
    }

    /**
     * A área desta corrida: o modelo com os quadros nos espaços, sem os que não existem aqui
     * ([available] — fotos desligadas, mapa desligado…). Um grupo que fica com um quadro só vira esse
     * quadro (o vizinho ocupa o espaço). null = nada para mostrar.
     */
    fun arrange(available: Set<MediaBlock> = MediaBlock.entries.toSet()): MediaArea? = fill(template.tree, available)

    private fun fill(node: MediaNode, available: Set<MediaBlock>): MediaArea? = when (node) {
        is MediaNode.Slot -> blocks[node.index].takeIf { it in available }?.let { MediaArea.Leaf(it) }
        is MediaNode.Split -> {
            val kids = node.children.mapNotNull { fill(it, available) }
            when (kids.size) {
                0 -> null
                1 -> kids.single()
                else -> MediaArea.Group(node.columns, kids)
            }
        }
    }

    /** "rows2b:map,pickup,dropoff" (sem ";" nem "=": vai dentro do texto salvo do Card e da notificação). */
    fun encode(): String = "${template.id}:${blocks.joinToString(",") { it.id }}"

    companion object {
        /** Card de antes: mapa em cima e as fotos A e destino lado a lado embaixo. */
        val CARD_DEFAULT = MediaLayout(MediaTemplate.ROWS_SECOND_SPLIT, listOf(MediaBlock.MAP, MediaBlock.PICKUP, MediaBlock.DROPOFF))

        /** Notificação de antes: mapa à esquerda e a coluna com A em cima e o destino embaixo. */
        val NOTICE_DEFAULT = MediaLayout(MediaTemplate.COLUMNS_SECOND_SPLIT, listOf(MediaBlock.MAP, MediaBlock.PICKUP, MediaBlock.DROPOFF))

        /** Texto salvo → layout; estranho ou vazio = [default]. */
        fun decode(text: String?, default: MediaLayout): MediaLayout {
            val parts = text?.trim()?.split(':') ?: return default
            if (parts.size != 2) return default
            val template = MediaTemplate.fromId(parts[0]) ?: return default
            val order = parts[1].split(',').mapNotNull { MediaBlock.fromId(it) }
            return MediaLayout(template, order)
        }

        private fun normalize(order: List<MediaBlock>): List<MediaBlock> {
            val seen = order.distinct()
            return seen + MediaBlock.entries.filter { it !in seen }
        }

        /** Altura natural da área (unidade de [sizes]): depende de como ela ficou depois de [arrange]. */
        fun height(area: MediaArea, sizes: MediaSizes): Int = when (area) {
            is MediaArea.Leaf -> sizes.single
            is MediaArea.Group -> if (area.columns) {
                if (area.children.any { it is MediaArea.Group }) sizes.splitColumns else sizes.columns
            } else {
                area.children.sumOf { rowHeight(it, area.children.size, sizes) } + sizes.gap * (area.children.size - 1)
            }
        }

        private fun rowHeight(child: MediaArea, rows: Int, sizes: MediaSizes): Int = when (child) {
            is MediaArea.Leaf -> if (rows >= 3) sizes.row3 else sizes.row2
            is MediaArea.Group -> sizes.rowGroup
        }

        /**
         * Retângulo de cada quadro numa área de [width] × [height] (px ou dp). Colunas dividem a
         * largura por igual; linhas dividem a altura na proporção das alturas de [sizes] (no Card o
         * mapa fica mais alto que a fileira de fotos). Sobra de arredondamento vai para o último.
         */
        fun place(area: MediaArea, width: Int, height: Int, sizes: MediaSizes, gap: Int = sizes.gap): List<MediaPlaced> {
            val out = ArrayList<MediaPlaced>(3)
            placeInto(area, 0, 0, width, height, sizes, gap, out)
            return out
        }

        private fun placeInto(area: MediaArea, x: Int, y: Int, w: Int, h: Int, sizes: MediaSizes, gap: Int, out: MutableList<MediaPlaced>) {
            when (area) {
                is MediaArea.Leaf -> out += MediaPlaced(area.block, x, y, w.coerceAtLeast(1), h.coerceAtLeast(1))
                is MediaArea.Group -> {
                    val n = area.children.size
                    val total = (if (area.columns) w else h) - gap * (n - 1)
                    val weights = if (area.columns) {
                        List(n) { 1.0 }
                    } else {
                        area.children.map { rowHeight(it, n, sizes).toDouble() }
                    }
                    val sum = weights.sum()
                    var pos = 0
                    area.children.forEachIndexed { i, child ->
                        val size = if (i == n - 1) total - pos else (total * weights[i] / sum).roundToInt()
                        if (area.columns) {
                            placeInto(child, x + pos + gap * i, y, size, h, sizes, gap, out)
                        } else {
                            placeInto(child, x, y + pos + gap * i, w, size, sizes, gap, out)
                        }
                        pos += size
                    }
                }
            }
        }
    }
}

/** Ponto de cada foto da área: coleta (A) e destino (última letra), localizados ou não. */
object MediaPhotos {
    fun stops(route: List<RouteStop>): Map<MediaBlock, RouteStop> = buildMap {
        route.firstOrNull { it.kind == StopKind.PICKUP }?.let { put(MediaBlock.PICKUP, it) }
        route.lastOrNull { it.kind == StopKind.DROPOFF }?.let { put(MediaBlock.DROPOFF, it) }
    }
}
