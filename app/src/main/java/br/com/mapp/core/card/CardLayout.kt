package br.com.mapp.core.card

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.media.MediaLayout
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Signal
import br.com.mapp.core.route.RideRoute
import br.com.mapp.core.route.StopKind
import java.util.Locale

/** Informações que podem ir para o cabeçalho do cartão da oferta. */
enum class CardMetric(val id: String, val label: String) {
    PRICE("price", "Valor da corrida"),
    TOTAL_TIME("total_time", "Tempo total"),
    TOTAL_KM("total_km", "Distância total"),
    RATING("rating", "Nota"),
    PICKUP("pickup", "Busca"),
    PER_KM("per_km", "R$/Km"),
    PER_HOUR("per_hour", "R$/Hora"),
    PER_MIN("per_min", "R$/Min"),
    NET_PROFIT("net", "Lucro (R$)"),
    NET_PER_HOUR("net_hour", "Lucro/Hora"),
    NET_PERCENT("net_pct", "Lucro %");

    companion object {
        fun fromId(id: String): CardMetric? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Itens da linha de endereços (embaixo das outras duas, texto pequeno). O Card mostra essa linha uma
 * vez por ponto da corrida: A = coleta (distância e tempo da busca), B, C… = paradas, última letra =
 * destino (distância e tempo da viagem, da coleta até ele).
 */
enum class AddressItem(val id: String, val label: String) {
    ADDRESS("address", "Endereço"),
    DISTANCE("distance", "Distância até o ponto"),
    TIME("time", "Tempo até o ponto");

    companion object {
        fun fromId(id: String): AddressItem? = entries.firstOrNull { it.id == id }
    }
}

enum class CardLine(val label: String, val slots: Int) {
    TOP("Linha de cima", 3),
    BOTTOM("Linha de baixo", 4),
}

/**
 * O que aparece no cabeçalho do cartão (estilo Rota PRO): duas linhas de informações, sem repetir
 * item. A linha de cima é texto corrido ("R$ 29,20 · 1h00m · ★ 4,92"); a de baixo, números grandes
 * com rótulo e barrinha de cor. Listas sem buracos: a posição é a ordem na lista.
 */
data class CardLayout(
    val top: List<CardMetric>,
    val bottom: List<CardMetric>,
    /** Mostra o nome do app (Uber/99/inDrive) no cartão. */
    val showPlatform: Boolean = true,
    /** Tamanho das letras da linha de cima, em % do padrão ([FONT_MIN]..[FONT_MAX]). */
    val topFont: Int = 100,
    /** Tamanho dos números grandes da linha de baixo, em % do padrão. */
    val bottomFont: Int = 100,
    /** Linha de endereços (uma por ponto A, B, C…); vazia = não aparece no Card. */
    val address: List<AddressItem> = emptyList(),
    /** Tamanho das letras da linha de endereços, em % do padrão. */
    val addressFont: Int = 100,
    /** Mapa e fotos embaixo do cabeçalho: modelo e lugar de cada quadro (Card de corridas → Mapa e fotos). */
    val media: MediaLayout = MediaLayout.CARD_DEFAULT,
) {
    fun font(line: CardLine): Int = if (line == CardLine.TOP) topFont else bottomFont

    /** Muda o tamanho das letras de uma linha (limitado e em passos de [FONT_STEP]). */
    fun withFont(line: CardLine, percent: Int): CardLayout {
        val v = clampFont(percent)
        return if (line == CardLine.TOP) copy(topFont = v) else copy(bottomFont = v)
    }

    fun line(line: CardLine): List<CardMetric> = if (line == CardLine.TOP) top else bottom

    /** Itens que ainda não estão em nenhuma linha. */
    val available: List<CardMetric> get() = CardMetric.entries.filter { it !in top && it !in bottom }

    fun positionOf(metric: CardMetric): Pair<CardLine, Int>? {
        top.indexOf(metric).takeIf { it >= 0 }?.let { return CardLine.TOP to it }
        bottom.indexOf(metric).takeIf { it >= 0 }?.let { return CardLine.BOTTOM to it }
        return null
    }

    /**
     * Coloca [metric] no espaço [index] de [line]. Espaço vazio: vai para o fim da linha.
     * Espaço ocupado: troca de lugar com quem estava lá (se [metric] vinha de outro espaço) ou
     * devolve o ocupante para os disponíveis.
     */
    fun place(metric: CardMetric, line: CardLine, index: Int): CardLayout {
        val from = positionOf(metric)
        val lists = mutableMapOf(CardLine.TOP to top.toMutableList(), CardLine.BOTTOM to bottom.toMutableList())
        val target = lists.getValue(line)
        val occupant = target.getOrNull(index)
        if (occupant == metric) return this
        if (occupant == null) {
            if (from == null && target.size >= line.slots) return this
            from?.let { (l, i) -> lists.getValue(l).removeAt(i) }
            if (lists.getValue(line).size >= line.slots) return this
            lists.getValue(line).add(metric)
        } else if (from != null) {
            lists.getValue(from.first)[from.second] = occupant
            target[index] = metric
        } else {
            target[index] = metric
        }
        return copy(top = lists.getValue(CardLine.TOP), bottom = lists.getValue(CardLine.BOTTOM))
    }

    fun remove(metric: CardMetric): CardLayout = copy(top = top - metric, bottom = bottom - metric)

    /** Itens da linha de endereços que ainda não estão nela. */
    val availableAddress: List<AddressItem> get() = AddressItem.entries.filter { it !in address }

    /** Mesmo jeito de [place], na linha de endereços: vazio vai para o fim; ocupado troca ou substitui. */
    fun placeAddress(item: AddressItem, index: Int): CardLayout {
        val list = address.toMutableList()
        val from = list.indexOf(item)
        val occupant = list.getOrNull(index)
        when {
            occupant == item -> return this
            occupant == null -> {
                if (from >= 0) list.removeAt(from)
                if (list.size >= ADDRESS_SLOTS) return this
                list.add(item)
            }
            from >= 0 -> { list[from] = occupant; list[index] = item }
            else -> list[index] = item
        }
        return copy(address = list)
    }

    fun removeAddress(item: AddressItem): CardLayout = copy(address = address - item)

    fun withAddressFont(percent: Int): CardLayout = copy(addressFont = clampFont(percent))

    fun encode(): String = buildString {
        append("top=").append(top.joinToString(",") { it.id })
        append(";bottom=").append(bottom.joinToString(",") { it.id })
        append(";platform=").append(if (showPlatform) 1 else 0)
        append(";topfont=").append(topFont)
        append(";bottomfont=").append(bottomFont)
        append(";address=").append(address.joinToString(",") { it.id })
        append(";addrfont=").append(addressFont)
        append(";media=").append(media.encode())
    }

    companion object {
        val DEFAULT = CardLayout(
            top = listOf(CardMetric.PRICE, CardMetric.TOTAL_TIME, CardMetric.RATING),
            bottom = listOf(CardMetric.PER_KM, CardMetric.PER_HOUR, CardMetric.NET_PROFIT),
        )

        /** Lê o texto salvo; qualquer coisa estranha volta para o [DEFAULT]. */
        fun decode(text: String?): CardLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val parts = text.split(';').mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
            }.toMap()
            if ("top" !in parts || "bottom" !in parts) return DEFAULT
            fun metrics(s: String?) = s.orEmpty().split(',').mapNotNull { CardMetric.fromId(it.trim()) }
            val top = metrics(parts["top"]).distinct().take(CardLine.TOP.slots)
            val bottom = metrics(parts["bottom"]).distinct().filter { it !in top }.take(CardLine.BOTTOM.slots)
            return CardLayout(
                top, bottom, parts["platform"] != "0",
                topFont = parts["topfont"]?.toIntOrNull()?.let(::clampFont) ?: 100,
                bottomFont = parts["bottomfont"]?.toIntOrNull()?.let(::clampFont) ?: 100,
                address = parts["address"].orEmpty().split(',').mapNotNull { AddressItem.fromId(it.trim()) }
                    .distinct().take(ADDRESS_SLOTS),
                addressFont = parts["addrfont"]?.toIntOrNull()?.let(::clampFont) ?: 100,
                media = MediaLayout.decode(parts["media"], MediaLayout.CARD_DEFAULT),
            )
        }

        /** Espaços da linha de endereços (cabem os 3 itens). */
        const val ADDRESS_SLOTS = 3

        const val FONT_MIN = 70
        const val FONT_MAX = 160
        const val FONT_STEP = 10

        fun clampFont(percent: Int): Int {
            val stepped = Math.round(percent / FONT_STEP.toDouble()).toInt() * FONT_STEP
            return stepped.coerceIn(FONT_MIN, FONT_MAX)
        }
    }
}

/** Texto de um item da linha de endereços. */
data class AddressCell(val item: AddressItem, val text: String)

/** Linha de endereço de um ponto da corrida: letra do mapa (A, B…), tipo do ponto e os itens. */
data class AddressRow(val letter: String, val kind: StopKind, val cells: List<AddressCell>)

/** Valores e cores de cada informação do cartão. */
object CardValues {
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    /** Forma da linha de cima: com unidade ("R$ 29,20", "1h00m", "★ 4,92", "R$ 1,05/km"). */
    fun full(metric: CardMetric, o: EvaluatedOffer): String = approx(metric, o, fullValue(metric, o))

    private fun fullValue(metric: CardMetric, o: EvaluatedOffer): String {
        val m = o.metrics
        return when (metric) {
            CardMetric.PRICE -> money(o.raw.price)
            CardMetric.TOTAL_TIME -> duration(m.totalMin)
            CardMetric.TOTAL_KM -> m.totalKm?.let { num(it, 1) + " km" } ?: "— km"
            CardMetric.RATING -> "★ " + (o.raw.passengerRating?.let { num(it, 2) } ?: "—")
            CardMetric.PICKUP -> "Busca " + pickup(o)
            CardMetric.PER_KM -> perKm(o)?.let { money(it) + "/km" } ?: "—/km"
            CardMetric.PER_HOUR -> m.perHour?.let { "R$ " + num(it, 0) + "/h" } ?: "—/h"
            CardMetric.PER_MIN -> perMin(o)?.let { money(it) + "/min" } ?: "—/min"
            CardMetric.NET_PROFIT -> "Lucro " + (m.netProfit?.let { money(it) } ?: "—")
            CardMetric.NET_PER_HOUR -> "Lucro " + (netPerHour(o)?.let { "R$ " + num(it, 0) + "/h" } ?: "—")
            CardMetric.NET_PERCENT -> "Lucro " + (netPercent(o)?.let { num(it, 0) + "%" } ?: "—")
        }
    }

    /** Forma da linha de baixo: só o número ("29,20", "1,05", "29", "62%"). */
    fun compact(metric: CardMetric, o: EvaluatedOffer): String = approx(metric, o, compactValue(metric, o))

    private fun compactValue(metric: CardMetric, o: EvaluatedOffer): String {
        val m = o.metrics
        return when (metric) {
            CardMetric.PRICE -> num(o.raw.price, 2)
            CardMetric.TOTAL_TIME -> duration(m.totalMin)
            CardMetric.TOTAL_KM -> m.totalKm?.let { num(it, 1) } ?: "—"
            CardMetric.RATING -> o.raw.passengerRating?.let { num(it, 2) } ?: "—"
            CardMetric.PICKUP -> o.raw.pickupKm?.let { num(it, 1) } ?: o.raw.pickupMin?.let { "${it}m" } ?: "—"
            CardMetric.PER_KM -> perKm(o)?.let { num(it, 2) } ?: "—"
            CardMetric.PER_HOUR -> m.perHour?.let { num(it, 0) } ?: "—"
            CardMetric.PER_MIN -> perMin(o)?.let { num(it, 2) } ?: "—"
            CardMetric.NET_PROFIT -> m.netProfit?.let { num(it, 2) } ?: "—"
            CardMetric.NET_PER_HOUR -> netPerHour(o)?.let { num(it, 0) } ?: "—"
            CardMetric.NET_PERCENT -> netPercent(o)?.let { num(it, 0) + "%" } ?: "—"
        }
    }

    /**
     * "~" na frente do que depende de um número que o leitor não conseguiu ler (tempo ou km de um
     * trecho estimado pela velocidade — ver OfferEvaluator.legs).
     */
    private fun approx(metric: CardMetric, o: EvaluatedOffer, text: String): String {
        if (text.contains('—')) return text // sem valor
        val legs = OfferEvaluator.legs(o.raw)
        val byTime = legs.timeEstimated && metric in TIME_METRICS
        val byKm = legs.kmEstimated && metric in KM_METRICS
        if (!byTime && !byKm) return text
        val label = LABELS.firstOrNull { text.startsWith(it) } ?: return "~$text"
        return label + "~" + text.substring(label.length)
    }

    private val TIME_METRICS = setOf(CardMetric.TOTAL_TIME, CardMetric.PER_HOUR, CardMetric.PER_MIN, CardMetric.NET_PER_HOUR)
    private val KM_METRICS = setOf(CardMetric.TOTAL_KM, CardMetric.PER_KM, CardMetric.NET_PROFIT, CardMetric.NET_PER_HOUR, CardMetric.NET_PERCENT)
    private val LABELS = listOf("Lucro ")

    /** Rótulo curto da linha de baixo. */
    fun shortLabel(metric: CardMetric): String = when (metric) {
        CardMetric.PRICE -> "Valor"
        CardMetric.TOTAL_TIME -> "Tempo"
        CardMetric.TOTAL_KM -> "Km total"
        CardMetric.PICKUP -> "Busca km"
        CardMetric.NET_PER_HOUR -> "Lucro/h"
        else -> metric.label
    }

    /**
     * Cor da informação pelo perfil ativo. Mais é melhor (R$/km, R$/h, valor, nota): vermelho abaixo
     * do mínimo, amarelo entre mínimo e "bom", verde a partir do "bom". Busca (menos é melhor, km e
     * minutos): vermelho acima do máximo, amarelo entre "bom" e máximo, verde até o "bom" — vale a
     * pior das duas. Null = sem critério para comparar (fica sem barrinha).
     */
    fun tone(metric: CardMetric, o: EvaluatedOffer, profile: FilterProfile?): Signal? {
        val m = o.metrics
        return when (metric) {
            CardMetric.PER_KM -> range(perKm(o), profile?.minPerKm, profile?.goodPerKm)
            CardMetric.PER_HOUR -> range(m.perHour, profile?.minPerHour, profile?.goodPerHour)
            CardMetric.PRICE -> range(o.raw.price, profile?.minPrice, profile?.goodPrice)
            CardMetric.RATING -> range(o.raw.passengerRating, profile?.minRating, profile?.goodRating)
            CardMetric.PICKUP -> worst(
                rangeMax(o.raw.pickupKm, profile?.maxPickupKm, profile?.goodPickupKm),
                rangeMax(o.raw.pickupMin?.toDouble(), profile?.maxPickupMin?.toDouble(), profile?.goodPickupMin?.toDouble()),
            )
            CardMetric.NET_PROFIT -> sign(m.netProfit)
            CardMetric.NET_PER_HOUR -> sign(netPerHour(o))
            CardMetric.NET_PERCENT -> sign(netPercent(o))
            CardMetric.TOTAL_TIME, CardMetric.TOTAL_KM, CardMetric.PER_MIN -> null
        }
    }

    /**
     * Corrida ótima ("tudo verde"): semáforo verde, R$/km e R$/hora lidos, e nenhuma informação do
     * Card ([layout]) amarela ou vermelha pelo perfil ativo. É quando toca o som de corrida ótima.
     */
    fun allGreen(o: EvaluatedOffer, profile: FilterProfile?, layout: CardLayout): Boolean {
        if (o.signal != Signal.GREEN) return false
        if (o.metrics.perKm == null || o.metrics.perHour == null) return false
        return (layout.top + layout.bottom).all { tone(it, o, profile).let { t -> t == null || t == Signal.GREEN } }
    }

    /**
     * Linhas de endereço do Card, uma por ponto do [RideRoute] (A coleta, B… paradas, última =
     * destino), com os itens de [items] na ordem escolhida. Distância/tempo: A = busca (você → A);
     * destino = viagem (A → destino, passando pelas paradas); parada = sem trecho próprio na Uber,
     * fica só o endereço ("Parada no caminho" quando a Uber não mostra). "~" = estimado.
     */
    fun addressRows(o: EvaluatedOffer, items: List<AddressItem>): List<AddressRow> {
        if (items.isEmpty()) return emptyList()
        val pickup = OfferEvaluator.pickupLeg(o.raw)
        val trip = OfferEvaluator.tripLeg(o.raw)
        return RideRoute.of(o).map { stop ->
            val leg = when (stop.kind) {
                StopKind.PICKUP -> pickup
                StopKind.DROPOFF -> trip
                StopKind.STOP -> null
            }
            val cells = items.mapNotNull { item ->
                when (item) {
                    AddressItem.ADDRESS -> AddressCell(
                        item,
                        stop.address ?: if (stop.kind == StopKind.STOP) STOP_TEXT else "Endereço não lido",
                    )
                    AddressItem.DISTANCE -> leg?.let { AddressCell(item, legKm(it)) }
                    AddressItem.TIME -> leg?.let { AddressCell(item, legTime(it)) }
                }
            }.ifEmpty { listOf(AddressCell(AddressItem.ADDRESS, STOP_TEXT)) }
            AddressRow(stop.letter, stop.kind, cells)
        }
    }

    private const val STOP_TEXT = "Parada no caminho"

    private fun legKm(leg: OfferEvaluator.Leg): String {
        val km = leg.km ?: return "— km"
        return (if (leg.kmEstimated) "~" else "") + num(km, 1) + " km"
    }

    private fun legTime(leg: OfferEvaluator.Leg): String {
        val min = leg.minutes ?: return "— min"
        return (if (leg.timeEstimated) "~" else "") + duration(Math.round(min).toInt())
    }

    private fun perKm(o: EvaluatedOffer) = o.metrics.perKm
    internal fun perMin(o: EvaluatedOffer) = o.metrics.totalMin?.takeIf { it > 0 }?.let { o.raw.price / it }
    internal fun netPerHour(o: EvaluatedOffer) =
        o.metrics.netProfit?.let { net -> o.metrics.totalMin?.takeIf { it > 0 }?.let { net / it * 60.0 } }
    internal fun netPercent(o: EvaluatedOffer) =
        o.metrics.netProfit?.let { net -> o.raw.price.takeIf { it > 0 }?.let { net / it * 100.0 } }

    private fun pickup(o: EvaluatedOffer): String {
        val km = o.raw.pickupKm?.let { num(it, 1) + " km" }
        val min = o.raw.pickupMin?.let { "$it min" }
        return listOfNotNull(km, min).joinToString(" · ").ifEmpty { "—" }
    }

    private fun range(value: Double?, min: Double?, good: Double?): Signal? {
        if (value == null || (min == null && good == null)) return null
        return when {
            min != null && value < min -> Signal.RED
            good != null && value < good -> Signal.YELLOW
            else -> Signal.GREEN
        }
    }

    /** Menos é melhor: acima de [max] = vermelho, acima de [good] = amarelo, senão verde. */
    private fun rangeMax(value: Double?, max: Double?, good: Double?): Signal? {
        if (value == null || (max == null && good == null)) return null
        return when {
            max != null && value > max -> Signal.RED
            good != null && value > good -> Signal.YELLOW
            else -> Signal.GREEN
        }
    }

    /** Pior cor: vermelho > amarelo > verde; null só se as duas forem null. */
    private fun worst(a: Signal?, b: Signal?): Signal? = when {
        a == Signal.RED || b == Signal.RED -> Signal.RED
        a == Signal.YELLOW || b == Signal.YELLOW -> Signal.YELLOW
        a == Signal.GREEN || b == Signal.GREEN -> Signal.GREEN
        else -> null
    }

    private fun sign(value: Double?): Signal? = value?.let { if (it < 0) Signal.RED else Signal.GREEN }

    /** "9 min", "1h00m". */
    fun duration(min: Int?): String = when {
        min == null -> "— min"
        min < 60 -> "$min min"
        else -> "${min / 60}h" + String.format(Locale.ROOT, "%02dm", min % 60)
    }

    fun money(v: Double): String = "R$ " + num(v, 2)

    fun num(v: Double, decimals: Int): String = String.format(PT_BR, "%.${decimals}f", v)
}
