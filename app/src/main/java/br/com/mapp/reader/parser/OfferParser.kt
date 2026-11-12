package br.com.mapp.reader.parser

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.PointRole
import br.com.mapp.core.model.RawOffer

/** Converte os textos visíveis da tela em uma oferta. Função pura: sem Android, testável na JVM. */
interface OfferParser {
    val platform: Platform

    /**
     * Devolve null se os textos não formam uma oferta (sem valor em R$, ou sem distância/tempo/endereço).
     *
     * [fromOcr] = os textos vieram da imagem da tela (OCR), não da árvore de Acessibilidade: endereços
     * chegam quebrados em 2–3 linhas, há ruído de ícones e a regra é mais rígida (botão de aceitar
     * ou os dois trechos completos).
     */
    fun parse(texts: List<String>, fromOcr: Boolean = false): RawOffer?

    /**
     * A tela ainda mostra [offer]? (algum trecho tempo+km igual ao dela). Serve para não "perder" a
     * oferta quando parte do cartão está coberta e o preço não pôde ser lido.
     */
    fun stillShows(texts: List<String>, offer: RawOffer): Boolean
}

object OfferParsers {
    fun forPlatform(platform: Platform): OfferParser = when (platform) {
        Platform.UBER -> UberParser
        Platform.NINETY_NINE -> NinetyNineParser
        Platform.INDRIVE -> InDriveParser
    }
}

/**
 * Uber Driver. Formato esperado (CALIBRAR com capturas reais):
 *   "UberX" · "R$ 18,40" · "★ 4,92" · "4 min (1,2 km) de distância" · "Rua A, 123 - Bairro"
 *   · "Viagem: 22 min (12,5 km)" · "Av. B, 456 - Bairro"
 * Busca = par com "de distância"/1º par; viagem = par com "Viagem"/2º par.
 */
object UberParser : BaseOfferParser(Platform.UBER) {
    // Na Uber o cartão da oferta aparece por cima da tela inicial (ganhos "R$ 0,00", "1-3 min", "+R$ 4"):
    // lê só o bloco do cartão.
    override val scopeToOfferBlock = true
    override val nonAddressTexts = listOf(
        "UberX", "Comfort", "Black", "Uber Black", "Uber Flash", "Flash", "Moto", "Uber Moto", "Juntos",
        "Priority", "Prioridade", "Bag", "Exclusivo", "Viagem", "Aceitar", "Corresponder",
    )
}

/**
 * 99 Motorista. Formato esperado (CALIBRAR com capturas reais):
 *   "99Pop" · "R$ 14,90" · "★ 4,95" · "Embarque: 3 min · 0,9 km" · "Rua C, 45 - Centro"
 *   · "Corrida: 18 min · 9,4 km" · "Av. D, 2000 - Jardim"
 */
object NinetyNineParser : BaseOfferParser(Platform.NINETY_NINE) {
    override val nonAddressTexts = listOf(
        "99Pop", "Pop", "99Comfort", "Comfort", "99Taxi", "Táxi", "Taxi", "99Moto", "Moto", "Pop Expresso",
        "99Entrega", "Entrega", "Negocia", "99 Negocia", "Aceitar", "Aceitar corrida",
    )
}

/**
 * inDrive. Formato esperado (CALIBRAR com capturas reais): o cartão costuma listar origem e destino
 * ANTES das distâncias, e o motorista pode ver botões com contrapropostas ("R$ 24", "R$ 26") DEPOIS
 * do preço do passageiro — por isso o 1º preço "puro" é o escolhido.
 *   "Carlos" · "★ 4,87 (152)" · "R$ 22" · "Rua E, 1500 - Bairro" · "Av. F, 900 - Bairro"
 *   · "1,4 km · 4 min" · "Viagem: 8,7 km · 21 min"
 */
object InDriveParser : BaseOfferParser(Platform.INDRIVE) {
    override val nonAddressTexts = listOf(
        "Cidade", "Moto", "Entrega", "Frete", "Intermunicipal", "Aceitar", "Oferecer seu preço",
    )
}

/** Implementação comum: as plataformas só trocam palavras-chave/listas. */
abstract class BaseOfferParser(final override val platform: Platform) : OfferParser {

    enum class LegKind { PICKUP, TRIP }

    /** Um par tempo/distância (ou só um deles) e as linhas onde apareceu. */
    data class Leg(
        val minutes: Int?,
        val km: Double?,
        val line: Int,
        val lineEnd: Int = line,
        val kind: LegKind? = null,
    ) {
        val partial: Boolean get() = minutes == null || km == null
        fun complements(o: Leg) = (minutes == null) != (o.minutes == null) && (km == null) != (o.km == null)
    }

    // Palavras (minúsculas, sem acento) perto de um par que indicam BUSCA. CALIBRAR.
    protected open val pickupKeywords: List<String> = listOf(
        "de distancia", "ate o passageiro", "ate passageiro", "ate o cliente", "ate voce", "de voce",
        "embarque", "buscar", "busca", "away", "pickup", "para chegar",
    )

    // Palavras perto de um par que indicam VIAGEM. CALIBRAR.
    protected open val tripKeywords: List<String> = listOf(
        "viagem", "corrida", "trajeto", "percurso", "destino", "trip", "duracao",
    )

    // Preços em linhas com estas palavras têm prioridade menor (dinâmico, promoções, ganhos do dia...).
    protected open val secondaryPriceKeywords: List<String> = listOf(
        "inclu", "dinamic", "promo", "bonus", "taxa", "gorjeta", "pedagio", "desconto", "ganhos", "saldo", "meta",
    )

    /** Textos exatos que nunca são endereço (categorias, botões). */
    protected open val nonAddressTexts: List<String> = emptyList()

    /** Botões que só existem no cartão de oferta (minúsculas, sem acento). Delimitam o fim do bloco. */
    protected open val acceptKeywords: List<String> = ParseUtils.ACCEPT_KEYWORDS

    /**
     * true = antes de ler, recorta só o bloco do cartão: do preço mais próximo ANTES do 1º par
     * completo (tempo + km) até o botão Aceitar. Evita misturar textos da tela de fundo.
     */
    protected open val scopeToOfferBlock: Boolean = false

    /** Se só existe UM par sem rótulo, ele é a viagem (true) ou a busca (false)? CALIBRAR. */
    protected open val singleUnlabeledLegIsTrip: Boolean = true

    // Entre tempo e distância de um mesmo par só pode haver sinais (nunca letras ou números):
    // "4 min (1,2 km)", "3 min · 0,9 km", e o que o OCR fizer do "•" ("6 min « 1.6 km", "29 minutos * 14.8 km").
    private val pairGap = Regex("""^[^\p{L}\d]{0,6}$""")

    override fun parse(texts: List<String>, fromOcr: Boolean): RawOffer? {
        val split = ParseUtils.splitLines(texts)
        // Árvore: textos repetidos (contentDescription do pai que repete os filhos) entram uma vez só.
        // OCR: não há repetição, e linhas iguais ("Porto Alegre" nos dois endereços) precisam ficar.
        val all = if (fromOcr) split.map(ParseUtils::cleanOcrLine) else split.distinct()
        val lines = if (scopeToOfferBlock) offerBlock(all, dedupe = !fromOcr) ?: return null else all
        val price = pickPrice(lines) ?: return null

        val legs = extractLegs(lines, dedupe = !fromOcr)
        val (pickup, trip) = classify(legs)
        val (pickupAddress, dropoffAddress) =
            (if (fromOcr) wrappedAddresses(lines, pickup, trip) else null) ?: pickAddresses(lines, legs, pickup, trip)

        // Só preço não basta (ex.: "Ganhos de hoje R$ 120,00" ou "R$ 0,00" + "1-3 min" na tela inicial):
        // precisa de um par completo (tempo + km) ou de um endereço.
        val fullLegs = legs.count { !it.partial }
        if (fullLegs == 0 && pickupAddress == null && dropoffAddress == null) return null
        // Imagem tem mais ruído (mapa, outras telas): só é oferta com o botão de aceitar ou com os
        // dois trechos completos (busca + viagem).
        if (fromOcr && fullLegs < 2 && lines.none(::isAcceptButton)) return null

        return RawOffer(
            platform = platform,
            price = price,
            tripKm = trip?.km,
            tripMin = trip?.minutes,
            pickupKm = pickup?.km,
            pickupMin = pickup?.minutes,
            pickupAddress = pickupAddress,
            dropoffAddress = dropoffAddress,
            passengerRating = lines.firstNotNullOfOrNull { ParseUtils.parseRating(it) },
            rawText = all.joinToString("\n"), // texto completo da tela: útil para calibrar
        )
    }

    // ------------------------------------------------------------------ bloco do cartão

    override fun stillShows(texts: List<String>, offer: RawOffer): Boolean {
        val lines = ParseUtils.splitLines(texts).map(ParseUtils::cleanOcrLine)
        return extractLegs(lines, dedupe = false).any { leg ->
            !leg.partial && (
                (leg.km == offer.tripKm && leg.minutes == offer.tripMin) ||
                    (leg.km == offer.pickupKm && leg.minutes == offer.pickupMin)
                )
        }
    }

    private fun isAcceptButton(line: String): Boolean = ParseUtils.isAcceptLine(line, acceptKeywords)

    /**
     * Valor da corrida numa linha: maior que zero ("R$ 0,00" = ganhos do dia), sem ser acréscimo
     * ("+R$ 4") nem taxa ("R$1,50/km").
     */
    private fun realPrice(line: String): Double? = ParseUtils.findMoney(line).firstOrNull {
        it.value > 0.0 && !line.substring(0, it.start).trimEnd().endsWith('+') && !ParseUtils.isRate(line, it.end)
    }?.value

    /** Recorte do cartão da oferta, ou null se não há par completo (tela sem oferta). */
    fun offerBlock(lines: List<String>, dedupe: Boolean = true): List<String>? {
        val firstFull = extractLegs(lines, dedupe).filter { !it.partial }.minOfOrNull { it.line } ?: return null
        val candidates = (firstFull downTo 0)
        val start = candidates.firstOrNull { ParseUtils.isMoneyOnly(lines[it]) && realPrice(lines[it]) != null }
            ?: candidates.firstOrNull { realPrice(lines[it]) != null }
            ?: return null
        val accept = (firstFull until lines.size).firstOrNull { isAcceptButton(lines[it]) }
        return lines.subList(start, (accept ?: lines.lastIndex) + 1)
    }

    // ------------------------------------------------------------------ preço

    private fun pickPrice(lines: List<String>): Double? {
        var best: Double? = null
        var bestPriority = Int.MAX_VALUE
        lines.forEach { line ->
            val folded = ParseUtils.fold(line)
            val priority = when {
                ParseUtils.isMoneyOnly(line) -> 0
                secondaryPriceKeywords.any { folded.contains(it) } -> 2
                else -> 1
            }
            if (priority >= bestPriority) return@forEach
            best = realPrice(line) ?: return@forEach
            bestPriority = priority
        }
        return best
    }

    // ------------------------------------------------------------------ pares tempo/distância

    private enum class Dim { MIN, KM }
    private class Item(val dim: Dim, val m: Measure)
    private class Group(val minutes: Int?, val km: Double?, val start: Int, val end: Int)

    /** [dedupe]: junta pares iguais repetidos (só faz sentido na árvore, onde pai e filho repetem o texto). */
    fun extractLegs(lines: List<String>, dedupe: Boolean = true): List<Leg> {
        val perLine = lines.mapIndexed { i, line -> legsInLine(line, i) }

        // Junta "3 min" e "1,2 km" quando vêm em linhas vizinhas, cada uma sozinha.
        val merged = mutableListOf<Leg>()
        var i = 0
        while (i < lines.size) {
            val cur = perLine[i]
            val next = perLine.getOrNull(i + 1)
            if (cur.size == 1 && next != null && next.size == 1 && cur[0].partial && next[0].partial &&
                cur[0].complements(next[0])
            ) {
                val a = cur[0]
                val b = next[0]
                merged += Leg(a.minutes ?: b.minutes, a.km ?: b.km, i, i + 1, a.kind ?: b.kind)
                i += 2
            } else {
                merged += cur
                i++
            }
        }

        // Rótulo em linha separada: "de distância" logo depois, ou "Viagem"/"Embarque" logo antes.
        val labeled = merged.map { leg ->
            if (leg.kind != null) return@map leg
            val after = lines.getOrNull(leg.lineEnd + 1)?.takeIf { isLabel(it) && isSuffixLabel(it) }
            val before = lines.getOrNull(leg.line - 1)?.takeIf { isLabel(it) && !isSuffixLabel(it) }
            val kind = after?.let { hintFor("", it) } ?: before?.let { hintFor(it, "") }
            leg.copy(kind = kind)
        }

        if (!dedupe) return labeled
        // Mesmo par repetido (texto do pai + do filho): fica o primeiro, herdando o rótulo se faltar.
        val out = mutableListOf<Leg>()
        for (leg in labeled) {
            val idx = out.indexOfFirst { it.minutes == leg.minutes && it.km == leg.km }
            if (idx < 0) out += leg
            else if (out[idx].kind == null && leg.kind != null) out[idx] = out[idx].copy(kind = leg.kind)
        }
        return out
    }

    private fun legsInLine(line: String, index: Int): List<Leg> {
        if (ParseUtils.hasStreetPrefix(line)) return emptyList() // "Rodovia SP-280, km 25" não é distância
        // Número que faz parte de um valor em R$ não é distância ("Est. de R$1,50 km" = taxa por km).
        val money = ParseUtils.findMoney(line)
        val items = (ParseUtils.findDurations(line).map { Item(Dim.MIN, it) } +
            ParseUtils.findDistances(line).map { Item(Dim.KM, it) })
            .filter { item -> money.none { it.start < item.m.end && item.m.start < it.end } }
            .sortedBy { it.m.start }
        if (items.isEmpty()) return emptyList()

        val groups = mutableListOf<Group>()
        var i = 0
        while (i < items.size) {
            val a = items[i]
            val b = items.getOrNull(i + 1)
            if (b != null && a.dim != b.dim && pairGap.matches(line.substring(a.m.end, b.m.start))) {
                groups += group(a, b)
                i += 2
            } else {
                groups += group(a, null)
                i++
            }
        }

        return groups.mapIndexed { k, g ->
            val prevEnd = groups.getOrNull(k - 1)?.end ?: 0
            val nextStart = groups.getOrNull(k + 1)?.start ?: line.length
            val before = line.substring(prevEnd, g.start)
            val after = line.substring(g.end, nextStart)
            Leg(g.minutes, g.km, index, index, hintFor(before, after))
        }
    }

    private fun group(a: Item, b: Item?): Group {
        val all = listOfNotNull(a, b)
        return Group(
            minutes = all.firstOrNull { it.dim == Dim.MIN }?.m?.value?.toInt(),
            km = all.firstOrNull { it.dim == Dim.KM }?.m?.value,
            start = all.minOf { it.m.start },
            end = all.maxOf { it.m.end },
        )
    }

    /** Palavra-chave mais próxima do par (depois dele ou antes dele) decide se é busca ou viagem. */
    private fun hintFor(before: String, after: String): LegKind? {
        val b = ParseUtils.fold(before)
        val a = ParseUtils.fold(after)
        var best: LegKind? = null
        var bestDistance = Int.MAX_VALUE
        fun consider(keywords: List<String>, kind: LegKind) {
            for (kw in keywords) {
                val ia = a.indexOf(kw)
                if (ia in 0 until bestDistance) { bestDistance = ia; best = kind }
                val ib = b.lastIndexOf(kw)
                if (ib >= 0) {
                    val d = b.length - (ib + kw.length)
                    if (d < bestDistance) { bestDistance = d; best = kind }
                }
            }
        }
        consider(pickupKeywords, LegKind.PICKUP)
        consider(tripKeywords, LegKind.TRIP)
        return best
    }

    private fun isLabel(line: String): Boolean {
        if (line.length > 40 || ParseUtils.hasMoney(line)) return false
        if (ParseUtils.findDurations(line).isNotEmpty() || ParseUtils.findDistances(line).isNotEmpty()) return false
        val f = ParseUtils.fold(line)
        return (pickupKeywords + tripKeywords).any { f.contains(it) }
    }

    /** "de distância", "de viagem" completam o par anterior; "Viagem", "Embarque" rotulam o próximo. */
    private fun isSuffixLabel(line: String): Boolean {
        val f = ParseUtils.fold(line).trim()
        return listOf("de ", "da ", "do ", "away", "trip").any { f.startsWith(it) }
    }

    fun classify(all: List<Leg>): Pair<Leg?, Leg?> {
        // Com dois pares completos (tempo + km), pedaços soltos ("1-3 min" do mapa) não entram na conta.
        val legs = if (all.count { !it.partial } >= 2) all.filter { !it.partial } else all
        var pickup = legs.firstOrNull { it.kind == LegKind.PICKUP }
        var trip = legs.firstOrNull { it.kind == LegKind.TRIP }
        val free = legs.filter { it.kind == null }.toMutableList()
        when {
            pickup == null && trip == null -> when {
                free.size >= 2 -> { pickup = free[0]; trip = free[1] } // ordem usual: busca, depois viagem
                free.size == 1 -> if (singleUnlabeledLegIsTrip) trip = free[0] else pickup = free[0]
            }
            pickup == null -> pickup = free.firstOrNull()
            trip == null -> trip = free.firstOrNull()
        }
        return pickup to trip
    }

    // ------------------------------------------------------------------ endereços

    /**
     * OCR: no layout "trecho → endereço → trecho → endereço" (cartão da Uber) cada endereço são as
     * linhas de texto logo abaixo do seu trecho, juntas. Devolve null se o layout não for esse.
     */
    private fun wrappedAddresses(lines: List<String>, pickup: Leg?, trip: Leg?): Pair<String?, String?>? {
        if (pickup == null || trip == null || pickup.lineEnd >= trip.line) return null
        val first = joinAddressLines(lines, pickup.lineEnd + 1, trip.line) ?: return null
        return first to joinAddressLines(lines, trip.lineEnd + 1, lines.size)
    }

    private fun joinAddressLines(lines: List<String>, from: Int, until: Int): String? {
        val parts = ArrayList<String>(MAX_ADDRESS_LINES)
        var i = from
        while (i < until && parts.size < MAX_ADDRESS_LINES && !isAcceptButton(lines[i]) &&
            ParseUtils.isAddressPart(lines[i], nonAddressTexts)
        ) {
            parts += lines[i]
            i++
        }
        if (parts.isEmpty()) return null
        val joined = parts.joinToString(" ")
        return ParseUtils.extractAddress(joined, nonAddressTexts)?.address ?: joined
    }

    private fun pickAddresses(lines: List<String>, legs: List<Leg>, pickup: Leg?, trip: Leg?): Pair<String?, String?> {
        val found = lines.mapIndexedNotNull { i, line ->
            ParseUtils.extractAddress(line, nonAddressTexts)?.let { i to it }
        }
        var pickupAddress = found.firstOrNull { it.second.role == PointRole.PICKUP }?.second?.address
        var dropoffAddress = found.firstOrNull { it.second.role == PointRole.DROPOFF }?.second?.address
        val unlabeled = found.filter { it.second.role == null }

        // Preferência: endereços DEPOIS do 1º par (Uber/99). Sem nenhum depois: os de antes (inDrive).
        val firstLegLine = legs.minOfOrNull { it.line }
        val after = if (firstLegLine == null) unlabeled else unlabeled.filter { it.first > firstLegLine }
        val pool = (after.ifEmpty { unlabeled }).map { it.first to it.second.address }.toMutableList()

        // Um único endereço logo após o par da viagem (e a busca antes dela) = destino.
        if (pickupAddress == null && dropoffAddress == null && pool.size == 1 &&
            pickup != null && trip != null && pickup.line < trip.line && pool[0].first > trip.lineEnd
        ) {
            return null to pool[0].second
        }
        if (pickupAddress == null) pickupAddress = pool.removeFirstOrNull()?.second
        if (dropoffAddress == null) dropoffAddress = pool.removeFirstOrNull()?.second
        return pickupAddress to dropoffAddress
    }

    private companion object {
        const val MAX_ADDRESS_LINES = 3
    }
}
