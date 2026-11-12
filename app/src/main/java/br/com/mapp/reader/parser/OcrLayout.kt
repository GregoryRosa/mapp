package br.com.mapp.reader.parser

import kotlin.math.abs
import kotlin.math.min

/** Linha de texto reconhecida na imagem da tela; coordenadas em pixels da imagem analisada. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2
}

/** Área da imagem a ignorar (ex.: janelas do próprio MApp por cima do app de corrida). */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun intersects(l: OcrLine): Boolean = l.left < right && left < l.right && l.top < bottom && top < l.bottom
}

/** Organiza o resultado do OCR para os parsers. Kotlin puro: testável na JVM. */
object OcrLayout {

    /**
     * Linhas do OCR → textos em ordem de leitura (de cima para baixo; na mesma faixa, da esquerda
     * para a direita). Descarta ruído de ícones (menos de 2 letras/números) e o que cai em [excluded].
     */
    fun toTexts(lines: List<OcrLine>, excluded: List<OcrBox> = emptyList()): List<String> {
        val kept = lines
            .filter { l -> isUseful(l.text) && excluded.none { it.intersects(l) } }
            .sortedWith(compareBy({ it.centerY }, { it.left }))

        val rows = ArrayList<MutableList<OcrLine>>()
        for (line in kept) {
            val row = rows.lastOrNull()
            val ref = row?.first()
            if (row != null && ref != null && abs(line.centerY - ref.centerY) <= min(ref.height, line.height) / 2) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.left }.map { it.text.trim() } }
    }

    // "R$" sozinho fica (o valor pode vir no pedaço seguinte); "X", "•", "O" de ícones saem.
    private fun isUseful(text: String): Boolean = text.count { it.isLetterOrDigit() } >= 2 || text.contains('$')
}
