package br.com.mapp.core.notify

/** Informações que podem ir para a notificação da corrida aceita (menu Card → Notificação). */
enum class NoticeItem(val id: String, val label: String) {
    KIND("kind", "Tipo (UberX…)"),
    PRICE("price", "Valor"),
    PICKUP("pickup", "Busca km · min"),
    TRIP("trip", "Viagem km · min"),
    TOTAL("total", "Total km · min"),
    ARRIVAL("arrival", "Chegada prevista"),
    PER_KM("per_km", "R$/Km"),
    PER_HOUR("per_hour", "R$/Hora"),
    NET("net", "Lucro"),
    NET_PER_HOUR("net_hour", "Lucro/Hora"),
    RATING("rating", "Nota"),
    PASSENGER("passenger", "Passageiro"),
    PICKUP_ADDRESS("pickup_addr", "Endereço da coleta"),
    DROPOFF_ADDRESS("dropoff_addr", "Endereço do destino"),
    ZONES("zones", "Zonas");

    companion object {
        fun fromId(id: String): NoticeItem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Linhas da notificação. [TITLE] e [TEXT] aparecem fechada e aberta; as [expanded] só aberta,
 * embaixo do mapa. Linha sem nada (ou sem valor lido) não ocupa espaço.
 */
enum class NoticeLine(val id: String, val label: String, val slots: Int, val expanded: Boolean) {
    TITLE("title", "Título", 3, false),
    TEXT("text", "Linha 2", 3, false),
    EXTRA1("x1", "Aberta · linha 1", 3, true),
    EXTRA2("x2", "Aberta · linha 2", 3, true),
    EXTRA3("x3", "Aberta · linha 3", 3, true),
    EXTRA4("x4", "Aberta · linha 4", 3, true),
    EXTRA5("x5", "Aberta · linha 5", 3, true),
}

/**
 * O que aparece em cada linha da notificação da corrida aceita, sem repetir item (igual ao
 * [br.com.mapp.core.card.CardLayout]). Listas sem buracos: a posição é a ordem na lista.
 * [headline] = "Corrida aceita" no começo do título; [showMap] = mapa na notificação aberta.
 */
data class NoticeLayout(
    val lines: Map<NoticeLine, List<NoticeItem>>,
    val headline: Boolean = true,
    val showMap: Boolean = true,
) {
    fun line(line: NoticeLine): List<NoticeItem> = lines[line].orEmpty()

    /** Itens que ainda não estão em nenhuma linha. */
    val available: List<NoticeItem>
        get() = NoticeItem.entries.filter { item -> NoticeLine.entries.none { item in line(it) } }

    fun positionOf(item: NoticeItem): Pair<NoticeLine, Int>? {
        NoticeLine.entries.forEach { l -> line(l).indexOf(item).takeIf { it >= 0 }?.let { return l to it } }
        return null
    }

    /**
     * Coloca [item] no espaço [index] de [line]. Espaço vazio: vai para o fim da linha. Espaço
     * ocupado: troca de lugar com quem estava lá (se [item] vinha de outro espaço) ou devolve o
     * ocupante para os disponíveis.
     */
    fun place(item: NoticeItem, line: NoticeLine, index: Int): NoticeLayout {
        val from = positionOf(item)
        val lists = NoticeLine.entries.associateWith { line(it).toMutableList() }
        val target = lists.getValue(line)
        val occupant = target.getOrNull(index)
        if (occupant == item) return this
        if (occupant == null) {
            if (from == null && target.size >= line.slots) return this
            from?.let { (l, i) -> lists.getValue(l).removeAt(i) }
            if (target.size >= line.slots) return this
            target.add(item)
        } else if (from != null) {
            lists.getValue(from.first)[from.second] = occupant
            target[index] = item
        } else {
            target[index] = item
        }
        return copy(lines = lists)
    }

    fun remove(item: NoticeItem): NoticeLayout = copy(lines = lines.mapValues { it.value - item })

    fun encode(): String = buildString {
        append("v1")
        NoticeLine.entries.forEach { l -> append(';').append(l.id).append('=').append(line(l).joinToString(",") { it.id }) }
        append(";headline=").append(if (headline) 1 else 0)
        append(";map=").append(if (showMap) 1 else 0)
    }

    companion object {
        /** Igual à notificação de antes desta tela. */
        val DEFAULT = NoticeLayout(
            mapOf(
                NoticeLine.TITLE to listOf(NoticeItem.KIND, NoticeItem.PRICE),
                NoticeLine.TEXT to listOf(NoticeItem.PICKUP, NoticeItem.TRIP),
                NoticeLine.EXTRA1 to listOf(NoticeItem.PER_KM, NoticeItem.PER_HOUR, NoticeItem.NET),
                NoticeLine.EXTRA2 to listOf(NoticeItem.TOTAL, NoticeItem.ARRIVAL),
                NoticeLine.EXTRA3 to listOf(NoticeItem.ZONES, NoticeItem.PASSENGER, NoticeItem.RATING),
                NoticeLine.EXTRA4 to listOf(NoticeItem.PICKUP_ADDRESS),
                NoticeLine.EXTRA5 to listOf(NoticeItem.DROPOFF_ADDRESS),
            ),
        )

        /** Lê o texto salvo; qualquer coisa estranha volta para o [DEFAULT]. */
        fun decode(text: String?): NoticeLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val parts = text.split(';')
            if (parts.firstOrNull() != "v1") return DEFAULT
            val map = parts.drop(1).mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
            }.toMap()
            if (NoticeLine.entries.none { it.id in map }) return DEFAULT
            val used = mutableSetOf<NoticeItem>()
            val lines = NoticeLine.entries.associateWith { l ->
                val items = map[l.id].orEmpty().split(',')
                    .mapNotNull { NoticeItem.fromId(it.trim()) }
                    .distinct()
                    .filter { it !in used }
                    .take(l.slots)
                used += items
                items
            }
            return NoticeLayout(lines, headline = map["headline"] != "0", showMap = map["map"] != "0")
        }
    }
}
