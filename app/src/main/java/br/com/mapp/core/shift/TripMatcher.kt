package br.com.mapp.core.shift

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.TripEvent
import br.com.mapp.core.model.TripStage

/**
 * Decide qual oferta foi aceita e quando as corridas abertas terminam, a partir das telas depois da
 * oferta (Kotlin puro).
 *
 * Problema que resolve: aceitando uma corrida **durante outra viagem**, a Uber continua mostrando
 * "Destino de <passageiro atual>"; o "Encontro com <novo>" só aparece quando a viagem atual termina,
 * muitos minutos depois da oferta. Por isso o nome do passageiro é a prova: **nome novo na tela =
 * corrida nova** → conta a oferta guardada na **memória de ofertas** (se ainda não contada).
 *
 * **Memória de ofertas:** guarda as últimas [MAX_OFFERS] ofertas (até [queuedMs]), não só a última —
 * a corrida é ligada à oferta mais recente que serve para ela. Oferta vista durante uma viagem vale
 * por até [queuedMs]; fora de viagem, por [freshMs] (a tela de oferta some ao aceitar). Quando uma
 * oferta é contada, as que ficaram para trás (recusadas/expiradas) saem da memória. A memória inteira
 * (ofertas, corridas abertas, passageiros que já saíram) é salva com [snapshot] e volta com [restore]:
 * o Android matar o copiloto no meio da viagem não perde mais a corrida aceita na fila.
 *
 * Etapas (Uber): "Encontro com" = aceita, indo buscar (ainda pode ser cancelada); "Destino de" =
 * passageiro embarcou, viagem confirmada; "Como foi a viagem?" = terminou; "Procurando viagens"
 * ([TripStage.IDLE]) = sem corrida.
 *
 * Telas que passaram rápido demais (aceite/fim muito rápidos, Waze na frente) são recuperadas depois:
 * - **aceite não lido**: o passageiro novo só aparece no embarque ou na volta para a Uber → conta a
 *   oferta vista até [lateMs] antes, se depois dela a tela inicial não apareceu (oferta recusada) e
 *   se a distância da tela combina com a oferta;
 * - **fim não lido**: outro passageiro na tela, ou a tela inicial "Procurando viagens" → as corridas
 *   abertas antes dele terminaram. Quem embarcou finaliza; quem não embarcou finaliza se já passou
 *   tempo para a corrida inteira (¾ do previsto), senão foi cancelada. Tela inicial menos de
 *   [settleMs] depois do aceite não fecha nada (pode ser só a transição da Uber).
 *
 * Proteções da corrida aceita na fila:
 * - **embarque cedo demais** ("Destino de <nome>" menos da metade da busca prevista depois da oferta)
 *   não é dessa oferta: é o passageiro que já estava a bordo (o MApp não viu a viagem começar) — a
 *   oferta passa a valer como aceita durante a viagem;
 * - **passageiro que já saiu** (corrida fechada há até [GHOST_MS]) aparecendo de novo na tela não é
 *   corrida nova, a não ser que tenha chegado oferta depois que ele saiu;
 * - corrida aberta **sem nome** (+1 ou aceite sem nome lido) não "pega" o nome do passageiro da fila
 *   quando há oferta mais nova que ela que serve para esse passageiro;
 * - **avaliação atrasada** (15/11/2026): na troca para a corrida da fila a Uber mostra "Encontro com
 *   <novo>" e só depois (ou já na corrida nova) "Como foi a viagem?" da corrida ANTERIOR. Corrida
 *   fechada sem a tela de fim deixa essa tela "devida" ([owed]); se ela chega quando a corrida aberta
 *   é nova demais para ter terminado, é da anterior ([Result.lateFinish]) e não fecha nada. Antes
 *   fechava a corrida recém-aceita: ela saía da jornada (ícone laranja) e o fim real virava
 *   "não vi o aceite". **Mesmo sem a anterior na memória** (perdida antes, copiloto reiniciado no meio
 *   dela): corrida que nem embarcou e é nova demais nunca termina por essa tela — fica marcada
 *   ([Rider.finishSeen]) e, se sair da tela sem embarque lido, conta como finalizada.
 *   **Na dúvida, a tela de fim não fecha** (errar para "atrasada" só adia o fechamento até a próxima
 *   tela; fechar errado perde a corrida): com avaliação devida, corrida sem embarque nunca fecha por
 *   ela; o relógio da corrida conta a partir da última tela de fim vista ([finishAt]) — telas de fim
 *   repetidas (a Uber deixa a avaliação na tela, texto de fundo) não fecham a corrida seguinte.
 * - **passageiro a bordo não é da oferta nova**: "Destino de <nome>" com corrida aberta sem nome
 *   (aceite lido sem nome, +1) é o passageiro DELA — a oferta que chegou depois fica na fila; e a
 *   distância que a tela mostra tem de dar tempo de ter sido rodada desde a oferta ([reachable]).
 *
 * [K] = identidade da oferta (o overlay usa app + horário da 1ª leitura).
 */
class TripMatcher<K : Any>(
    private val freshMs: Long = 90_000L,
    private val queuedMs: Long = 60 * 60_000L,
    private val lateMs: Long = 20 * 60_000L,
    private val settleMs: Long = 60_000L,
) {
    data class Result<K>(
        /** Oferta a contar como corrida aceita (null = nada a contar). */
        val count: K?,
        /** A tela indica corrida nova (passageiro novo ou aceite sem nome), com ou sem oferta para contar. */
        val newRide: Boolean,
        /** Corrida aberta sem embarque terminou cancelada: tirar a corrida em andamento mais recente. */
        val cancelledPickup: Boolean = false,
        /** Passageiro embarcou agora ("Destino de"): viagem confirmada. */
        val started: Boolean = false,
        /** Corridas abertas que terminaram sem a tela de fim ter sido lida: finalizar (da mais antiga). */
        val finishedUnseen: Int = 0,
        /** App das corridas a finalizar/cancelar (null = qualquer). */
        val platform: Platform? = null,
        /**
         * "Como foi a viagem?" de uma corrida já fechada (avaliação atrasada da anterior, na troca para
         * a corrida da fila): não finalizar nada.
         */
        val lateFinish: Boolean = false,
    )

    /** Oferta na memória. */
    private class Seen<K>(
        val key: K,
        val firstAt: Long,
        var lastAt: Long,
        /** Apareceu com corrida aberta: se aceita, fica na fila até a viagem atual acabar. */
        var duringTrip: Boolean,
        var platform: Platform?,
        var pickupKm: Double?,
        var tripKm: Double?,
        var pickupMin: Int?,
        var expectedMs: Long?,
        /** 1ª tela inicial ("Procurando viagens") vista depois dela: o app voltou a procurar. */
        var idleAt: Long? = null,
    )

    /**
     * Corrida aberta. [counted] = está na jornada (oferta contada ou +1); só essas geram
     * finalizar/cancelar — as outras só guardam o nome para não virar corrida nova de novo.
     */
    private class Rider(
        var name: String?,
        var started: Boolean,
        val since: Long,
        val expectedMs: Long?,
        var counted: Boolean,
        val platform: Platform?,
        /** "Como foi a viagem?" apareceu com ela aberta mas nova demais (tratada como da anterior). */
        var finishSeen: Boolean = false,
    )

    /** Passageiro de corrida já fechada (para resto de tela não virar corrida nova). */
    private class Gone(val name: String, val platform: Platform?, val at: Long)

    /** Corrida finalizada sem a tela de fim ter aparecido: a avaliação dela ainda pode vir. */
    private class Owed(val platform: Platform?, val at: Long)

    private enum class Ending { FINISHED, CANCELLED, WAIT }

    private enum class Fit { OK, NO, TOO_YOUNG }

    /** Ofertas, da mais antiga para a mais nova (1ª leitura). */
    private val offers = ArrayList<Seen<K>>()
    private val counted = LinkedHashSet<K>()
    /** Corridas abertas, da mais antiga para a mais nova. */
    private val riders = ArrayDeque<Rider>()
    private val gone = ArrayDeque<Gone>()
    private val owed = ArrayDeque<Owed>()
    /** Última tela de fim vista por app (qualquer uma, inclusive as tratadas como atrasadas). */
    private val finishAt = HashMap<String, Long>()

    /** Muda a cada alteração que vale salvar ([snapshot]); só reler a mesma oferta não muda. */
    var changes: Int = 0
        private set

    /** Corridas abertas que estão na jornada. */
    val openRides: Int get() = riders.count { it.counted }

    /** Resumo da memória para o registro de diagnóstico: corridas abertas, ofertas e avaliações devidas. */
    fun describe(): String {
        val open = riders.joinToString(", ") { r ->
            listOf(r.name ?: "?", if (r.started) "a bordo" else "busca", if (r.counted) "contada" else "sem oferta")
                .joinToString("/") + if (r.finishSeen) "/fim visto" else ""
        }
        val waiting = offers.count { it.key !in counted }
        val queued = offers.count { it.key !in counted && it.duringTrip }
        return "abertas=[$open] ofertas=$waiting (fila=$queued) devidas=${owed.size}"
    }

    /** Ofertas na memória que ainda podem virar corrida (diagnóstico/testes). */
    val rememberedOffers: Int get() = offers.count { it.key !in counted }

    /**
     * Oferta no cartão agora (chamar a cada atualização do cartão). [pickupKm]/[tripKm] conferem a
     * distância das telas seguintes; [expectedMs] = duração prevista (busca + viagem);
     * [pickupMin] = tempo previsto da busca (embarque antes da metade dele não é desta oferta).
     */
    fun offerSeen(
        key: K,
        now: Long,
        pickupKm: Double? = null,
        tripKm: Double? = null,
        expectedMs: Long? = null,
        platform: Platform? = null,
        pickupMin: Int? = null,
    ) {
        prune(now)
        val o = offers.firstOrNull { it.key == key }
        if (o == null) {
            offers += Seen(key, now, now, riders.isNotEmpty(), platform, pickupKm, tripKm, pickupMin, expectedMs)
            while (offers.size > MAX_OFFERS) offers.removeAt(0)
            changes++
            return
        }
        o.lastAt = now
        o.platform = platform ?: o.platform
        o.pickupKm = pickupKm ?: o.pickupKm
        o.tripKm = tripKm ?: o.tripKm
        o.pickupMin = pickupMin ?: o.pickupMin
        o.expectedMs = expectedMs ?: o.expectedMs
    }

    /**
     * Oferta contada (automático ou +1): não conta de novo. As ofertas que ficaram para trás dela
     * (vistas antes, ou junto — o mesmo cartão relido) saem da memória: não foram aceitas.
     */
    fun markCounted(key: K) {
        counted.remove(key)
        counted.add(key)
        while (counted.size > MAX_KEYS) counted.remove(counted.first())
        val o = offers.firstOrNull { it.key == key }
        if (o != null) {
            offers.removeAll {
                it !== o && it.key !in counted && samePlatform(it.platform, o.platform) &&
                    it.firstAt <= o.lastAt + SAME_CARD_MS
            }
        }
        changes++
    }

    /**
     * +1 manual com a corrida em andamento: vira corrida aberta (fecha sozinha depois). Se a tela já
     * mostrou um passageiro sem oferta contada, a corrida é dele.
     */
    fun manualCount(key: K, now: Long) {
        markCounted(key)
        val open = riders.lastOrNull { !it.counted }
        if (open != null) {
            open.counted = true
        } else {
            val o = offers.firstOrNull { it.key == key }
            riders.addLast(Rider(null, false, now, o?.expectedMs, counted = true, platform = o?.platform))
            trim()
        }
    }

    /** Corrida desfeita/cancelada: a oferta pode ser contada de novo. */
    fun forget(key: K) {
        counted.remove(key)
        changes++
    }

    /** Finalizada à mão (✓ no painel): sai a corrida aberta mais antiga. */
    fun closedOldest(now: Long = 0L) {
        riders.firstOrNull { it.counted }?.let { close(it, now) }
        changes++
    }

    /** Cancelada ou desfeita à mão (✕ / ↶): sai a corrida aberta mais recente. */
    fun closedNewest(now: Long = 0L) {
        riders.lastOrNull { it.counted }?.let { close(it, now) }
        changes++
    }

    fun reset() {
        offers.clear()
        counted.clear()
        riders.clear()
        gone.clear()
        owed.clear()
        finishAt.clear()
        changes++
    }

    fun onStage(event: TripEvent, now: Long): Result<K> {
        prune(now)
        changes++
        return when (event.stage) {
            TripStage.FINISHED -> finished(event.platform, now)
            TripStage.IDLE -> idle(event.platform, now)
            TripStage.ACCEPTED, TripStage.ON_TRIP -> active(event, now)
        }.copy(platform = event.platform)
    }

    // ------------------------------------------------------------------ etapas

    private fun finished(platform: Platform?, now: Long): Result<K> {
        // Quem terminou: o mais antigo daquele app que já embarcou (ou o mais antigo, se o embarque não foi lido).
        val here = riders.filter { samePlatform(platform, it.platform) }
        val done = here.firstOrNull { it.started } ?: here.firstOrNull()
        // Avaliação atrasada da corrida anterior (já fechada quando o passageiro novo apareceu).
        val late = owed.firstOrNull { samePlatform(platform, it.platform) }
        val slot = platform?.name.orEmpty()
        val prev = finishAt[slot]
        finishAt[slot] = now
        if (done == null) {
            if (late != null) owed.remove(late)
            return Result(null, newRide = false, lateFinish = late != null)
        }
        // Relógio da corrida: desde que ela abriu ou desde a última tela de fim (o que veio depois) —
        // tela de fim repetida logo em seguida não pode ser de uma corrida que mal começou.
        val young = tooYoungToFinish(done, now - maxOf(done.since, prev ?: Long.MIN_VALUE))
        val isLate = if (!done.started) {
            // Nem embarcou: com avaliação devida a tela é da anterior; sem ela, só fecha se já deu
            // tempo da corrida inteira (embarque não lido).
            late != null || young
        } else {
            // A bordo: só é da anterior se há sinal disso (avaliação devida, ou tela de fim vista na troca).
            young && (late != null || (prev != null && prev >= done.since - SWAP_MS))
        }
        if (isLate) {
            if (late != null) owed.remove(late)
            if (!done.started) done.finishSeen = true
            return Result(null, newRide = false, lateFinish = true)
        }
        close(done, now)
        return Result(null, newRide = false)
    }

    /**
     * Corrida aberta há pouco demais ([elapsed]) para já ter terminado: sem embarque, menos de ¾ do
     * previsto; com embarque, menos da metade.
     */
    private fun tooYoungToFinish(r: Rider, elapsed: Long): Boolean {
        val full = r.expectedMs ?: UNKNOWN_EXPECTED_MS
        return if (r.started) elapsed * 2 < full else elapsed * 4 < full * 3
    }

    /** Tela inicial: nenhuma corrida em andamento NESTE app ([platform]); as dos outros apps seguem. */
    private fun idle(platform: Platform?, now: Long): Result<K> {
        // Ofertas que não viraram corrida e o app voltou a procurar: foram recusadas ou expiraram.
        for (o in offers) {
            if (o.key !in counted && o.idleAt == null && samePlatform(platform, o.platform)) o.idleAt = now
        }
        var finished = 0
        var cancelled = false
        for (r in riders.toList().filter { samePlatform(platform, it.platform) }) {
            when (ending(r, now, wait = true)) {
                Ending.FINISHED -> { close(r, now); if (r.counted) finished++ }
                Ending.CANCELLED -> { close(r, now); if (r.counted) cancelled = true }
                Ending.WAIT -> Unit
            }
        }
        return Result(null, newRide = false, cancelledPickup = cancelled, finishedUnseen = finished)
    }

    private fun active(event: TripEvent, now: Long): Result<K> {
        val boarding = event.stage == TripStage.ON_TRIP
        val name = event.passenger
        if (name == null) {
            if (boarding) {
                riders.firstOrNull { !it.started }?.started = true
                return Result(null, newRide = false)
            }
            // Aceite sem nome (OCR cortou): é a busca já aberta, ou corrida nova só com oferta recente.
            if (riders.any { !it.started }) return Result(null, newRide = false)
            val o = pick(event, now, named = false, boarding = false)
            riders.addLast(Rider(null, false, now, o?.expectedMs, counted = o != null, platform = event.platform))
            trim()
            return Result(o?.key, newRide = true)
        }
        val known = riders.firstOrNull { r -> r.name?.let { sameName(it, name) } == true }
        if (known == null && isGhost(name, event.platform)) return Result(null, newRide = false)
        val rider = known ?: adopt(name, event, now, boarding)
        if (rider != null) {
            val started = boarding && !rider.started
            if (started) rider.started = true
            val closed = closeBefore(rider, now)
            return Result(null, newRide = false, started = started, cancelledPickup = closed.second, finishedUnseen = closed.first)
        }
        val o = pick(event, now, named = true, boarding = boarding)
        if (boarding) markQueued(event, now, except = o)
        val fresh = Rider(name, boarding, now, o?.expectedMs, counted = o != null, platform = event.platform)
        riders.addLast(fresh)
        val closed = closeBefore(fresh, now)
        trim()
        return Result(
            o?.key,
            newRide = true,
            cancelledPickup = closed.second,
            started = boarding,
            finishedUnseen = closed.first,
        )
    }

    /**
     * Corrida aberta sem nome (aceite lido sem nome ou +1 manual) recebe o nome que apareceu — a não
     * ser que haja oferta mais nova que ela que sirva para este passageiro (é o passageiro da fila)
     * e ela já não esteja só começando.
     */
    private fun adopt(name: String, event: TripEvent, now: Long, boarding: Boolean): Rider? {
        val r = riders.firstOrNull { it.name == null && (boarding || !it.started) } ?: return null
        val newer = pick(event, now, named = true, boarding = boarding, after = r.since + SAME_CARD_MS)
        // A bordo ("Destino de"): é o passageiro desta corrida, a não ser que já tenha dado tempo de ela
        // acabar — a oferta mais nova foi aceita durante a viagem e fica na fila. Antes, sem o embarque
        // lido, a oferta nova era contada para quem já estava a bordo (e a corrida dele, cancelada).
        val giveUp = if (boarding) likelyOver(r, now) else !r.started || likelyOver(r, now)
        if (newer != null && giveUp) return null
        if (boarding) {
            for (o in offers) {
                if (o.key !in counted && o.firstAt > r.since && samePlatform(event.platform, o.platform)) o.duringTrip = true
            }
        }
        r.name = name
        return r
    }

    /** Nome de quem já saiu (corrida fechada há pouco) sem oferta nova depois: resto de tela. */
    private fun isGhost(name: String, platform: Platform?): Boolean {
        val g = gone.lastOrNull { sameName(it.name, name) && samePlatform(platform, it.platform) } ?: return false
        return offers.none { it.firstAt > g.at && samePlatform(platform, it.platform) }
    }

    /**
     * Embarque de passageiro desconhecido logo depois de uma oferta: ela não é dele (não dá tempo de
     * buscar) — foi aceita/vista durante a viagem dele, então vale como corrida na fila.
     */
    private fun markQueued(event: TripEvent, now: Long, except: Seen<K>? = null) {
        for (o in offers) {
            if (o === except || o.key in counted || !samePlatform(event.platform, o.platform)) continue
            // Mais nova que a oferta deste passageiro, cedo demais para a busca, ou longe demais para
            // ter sido rodada: chegou com ele a bordo.
            val newer = except != null && o.firstAt > except.lastAt + SAME_CARD_MS
            if (newer || now - o.lastAt < minBoardMs(o) || !reachable(o, now - o.lastAt, event.km)) o.duringTrip = true
        }
    }

    /**
     * [rider] está na tela: as corridas abertas antes dele terminaram (a Uber só mostra o próximo
     * passageiro depois que a viagem atual acaba). Devolve (finalizadas, alguma cancelada).
     */
    private fun closeBefore(rider: Rider, now: Long): Pair<Int, Boolean> {
        val index = riders.indexOf(rider)
        if (index <= 0) return 0 to false
        var finished = 0
        var cancelled = false
        val here = rider.platform
        for (r in riders.subList(0, index).toList()) {
            // Passageiro de um app não encerra corrida aberta de outro.
            if (here != null && r.platform != null && r.platform != here) continue
            close(r, now)
            when (ending(r, now, wait = false)) {
                Ending.FINISHED -> {
                    if (r.counted) finished++
                    // A Uber pode mostrar a avaliação desta corrida só agora, já com o passageiro novo.
                    owed.addLast(Owed(r.platform, now))
                    while (owed.size > MAX_OWED) owed.removeFirst()
                }
                else -> if (r.counted) cancelled = true
            }
        }
        return finished to cancelled
    }

    private fun close(r: Rider, now: Long) {
        riders.remove(r)
        r.name?.let { gone.addLast(Gone(it, r.platform, now)) }
        while (gone.size > MAX_GONE) gone.removeFirst()
    }

    /** Corrida aberta que saiu da tela: terminou ou foi cancelada? */
    private fun ending(r: Rider, now: Long, wait: Boolean): Ending {
        if (r.started || r.finishSeen) return Ending.FINISHED
        val elapsed = now - r.since
        if (wait && elapsed < settleMs) return Ending.WAIT
        return if (likelyOver(r, now)) Ending.FINISHED else Ending.CANCELLED
    }

    /** Já passou tempo para a corrida inteira (¾ do previsto)? */
    private fun likelyOver(r: Rider, now: Long): Boolean {
        val full = r.expectedMs ?: UNKNOWN_EXPECTED_MS
        return (now - r.since) * 4 >= full * 3
    }

    // ------------------------------------------------------------------ memória de ofertas

    /**
     * Oferta da memória para a corrida nova desta tela: a mais recente (não contada, do mesmo app,
     * vista depois de [after]) que serve. Embarque cedo demais para uma oferta para a busca: as mais
     * velhas também não são deste passageiro.
     */
    private fun pick(event: TripEvent, now: Long, named: Boolean, boarding: Boolean, after: Long = Long.MIN_VALUE): Seen<K>? {
        val list = offers
            .filter { it.key !in counted && it.firstAt >= after && samePlatform(event.platform, it.platform) }
            .sortedByDescending { it.lastAt }
        for (o in list) {
            when (fit(o, now, named, boarding, event.km)) {
                Fit.OK -> return o
                Fit.TOO_YOUNG -> return null
                Fit.NO -> Unit
            }
        }
        return null
    }

    private fun fit(o: Seen<K>, now: Long, named: Boolean, boarding: Boolean, screenKm: Double?): Fit {
        val age = now - o.lastAt
        if (boarding && age < minBoardMs(o)) return Fit.TOO_YOUNG
        // A bordo com menos km pela frente do que daria para ter rodado desde a oferta: é o passageiro
        // de antes dela (a tela foi lida pela 1ª vez no meio da viagem dele).
        if (boarding && !reachable(o, age, screenKm)) return Fit.NO
        if (age <= freshMs) return Fit.OK
        if (!named) return Fit.NO
        val idle = o.idleAt
        // Na fila: vale até a viagem atual acabar (uma tela inicial rápida na troca não derruba).
        if (o.duringTrip && age <= queuedMs && (idle == null || now - idle <= IDLE_GRACE_MS)) return Fit.OK
        // Aceite não lido: o passageiro aparece só minutos depois (embarque ou volta do Waze).
        if (idle == null && age <= lateMs && plausible(o, screenKm, boarding)) return Fit.OK
        return Fit.NO
    }

    /** Distância que a tela mostra cabe na oferta? (folga para desvio/trânsito; sem dado = cabe). */
    private fun plausible(o: Seen<K>, screenKm: Double?, boarding: Boolean): Boolean {
        val leg = if (boarding) o.tripKm else o.pickupKm
        if (screenKm == null || leg == null) return true
        return screenKm <= leg * 1.5 + 1.0
    }

    /**
     * Passageiro a bordo com [screenKm] até o destino: dá para ser desta oferta? O que já foi rodado
     * da viagem (km da oferta − km da tela) precisa caber no tempo desde o embarque mais cedo possível,
     * a no máx. [MAX_KM_PER_MIN]. Sem os dados = cabe.
     */
    private fun reachable(o: Seen<K>, age: Long, screenKm: Double?): Boolean {
        val trip = o.tripKm ?: return true
        val driven = trip - (screenKm ?: return true)
        if (driven <= REACH_SLACK_KM) return true
        val minutes = (age - minBoardMs(o)).coerceAtLeast(0L) / 60_000.0
        return driven <= minutes * MAX_KM_PER_MIN + REACH_SLACK_KM
    }

    /** Embarque antes disto depois da oferta não é dela: metade da busca prevista (30 s – 5 min). */
    private fun minBoardMs(o: Seen<K>): Long =
        ((o.pickupMin?.let { it * 60_000L } ?: DEFAULT_PICKUP_MS) / 2).coerceIn(30_000L, 5 * 60_000L)

    private fun prune(now: Long) {
        offers.removeAll { now - it.lastAt > queuedMs }
        riders.removeAll { now - it.since > MAX_RIDE_MS }
        gone.removeAll { now - it.at > GHOST_MS }
        owed.removeAll { now - it.at > OWED_MS }
        finishAt.values.removeAll { now - it > OWED_MS }
    }

    private fun trim() {
        while (riders.size > MAX_RIDERS) riders.removeFirst()
    }

    private fun samePlatform(a: Platform?, b: Platform?) = a == null || b == null || a == b

    // ------------------------------------------------------------------ salvar / restaurar

    /**
     * Memória inteira em texto (ofertas, corridas abertas, quem saiu, contadas), para salvar no
     * aparelho. [encode] = chave da oferta em texto sem tab nem quebra de linha.
     */
    fun snapshot(now: Long, encode: (K) -> String): String = buildString {
        append(listOf(VERSION, now).joinToString(SEP))
        offers.forEach { o ->
            row(
                "o", encode(o.key), o.firstAt, o.lastAt, if (o.duringTrip) 1 else 0, o.platform?.name,
                o.pickupKm, o.tripKm, o.pickupMin, o.expectedMs, o.idleAt,
            )
        }
        riders.forEach { r ->
            row(
                "r", r.name, if (r.started) 1 else 0, r.since, r.expectedMs, if (r.counted) 1 else 0, r.platform?.name,
                if (r.finishSeen) 1 else 0,
            )
        }
        gone.forEach { g -> row("g", g.name, g.platform?.name, g.at) }
        owed.forEach { f -> row("f", f.platform?.name, f.at) }
        finishAt.forEach { (slot, at) -> row("e", slot, at) }
        counted.forEach { k -> row("c", encode(k)) }
    }

    /**
     * Volta a memória salva por [snapshot] (copiloto reaberto). Memória salva há mais de
     * [MAX_RESTORE_MS] não vale (dia/jornada já mudou). Devolve se restaurou.
     */
    fun restore(text: String?, now: Long, decode: (String) -> K?): Boolean {
        if (text.isNullOrBlank()) return false
        val rows = text.split('\n')
        val head = rows.first().split(SEP)
        if (head.getOrNull(0) != VERSION) return false
        val savedAt = head.getOrNull(1)?.toLongOrNull() ?: return false
        if (now - savedAt > MAX_RESTORE_MS || savedAt > now + 60_000L) return false
        return try {
            reset()
            for (row in rows.drop(1)) {
                val c = row.split(SEP)
                when (c[0]) {
                    "o" -> decode(c[1])?.let { k ->
                        offers += Seen(
                            k, c[2].toLong(), c[3].toLong(), c[4] == "1", platformOf(c[5]),
                            c[6].toDoubleOrNull(), c[7].toDoubleOrNull(), c[8].toIntOrNull(), c[9].toLongOrNull(),
                            c[10].toLongOrNull(),
                        )
                    }
                    "r" -> riders.addLast(
                        Rider(
                            c[1].ifEmpty { null }, c[2] == "1", c[3].toLong(), c[4].toLongOrNull(), c[5] == "1", platformOf(c[6]),
                            finishSeen = c.getOrNull(7) == "1",
                        ),
                    )
                    "g" -> gone.addLast(Gone(c[1], platformOf(c[2]), c[3].toLong()))
                    "f" -> owed.addLast(Owed(platformOf(c[1]), c[2].toLong()))
                    "e" -> finishAt[c[1]] = c[2].toLong()
                    "c" -> decode(c[1])?.let { counted.add(it) }
                }
            }
            prune(now)
            true
        } catch (e: RuntimeException) {
            reset()
            false
        }
    }

    private fun StringBuilder.row(vararg cells: Any?) {
        append('\n')
        append(cells.joinToString(SEP) { cell -> cell?.toString()?.replace(SEP, " ")?.replace("\n", " ") ?: "" })
    }

    private fun platformOf(s: String?): Platform? = s?.takeIf { it.isNotEmpty() }?.let { runCatching { Platform.valueOf(it) }.getOrNull() }

    companion object {
        private const val MAX_KEYS = 50
        private const val MAX_RIDERS = 4
        /** Ofertas guardadas na memória. */
        const val MAX_OFFERS = 12
        private const val MAX_GONE = 6
        private const val MAX_OWED = 3
        /** Avaliação atrasada da corrida anterior vale até isto depois de ela ser fechada. */
        private const val OWED_MS = 60 * 60_000L
        /** Tela de fim vista até isto antes de a corrida abrir conta como "na troca de corrida". */
        private const val SWAP_MS = 2 * 60_000L
        /** Velocidade máxima plausível (90 km/h) e folga para conferir os km da tela a bordo. */
        private const val MAX_KM_PER_MIN = 1.5
        private const val REACH_SLACK_KM = 1.0
        private const val VERSION = "tm1"
        private const val SEP = "\t"

        /** Oferta vista até isto depois da contada é o mesmo cartão relido (ou recusada junto). */
        private const val SAME_CARD_MS = 30_000L
        /** Tela inicial rápida na troca de corrida (fila): a oferta na fila continua valendo por isto. */
        private const val IDLE_GRACE_MS = 2 * 60_000L
        /** Busca prevista desconhecida (embarque antes da metade disto não é da oferta). */
        private const val DEFAULT_PICKUP_MS = 2 * 60_000L
        /** Quem saiu há até isto e aparece de novo na tela é resto de tela, não corrida nova. */
        private const val GHOST_MS = 30 * 60_000L
        /** Corrida aberta há mais que isto (nunca fechou) sai da memória. */
        private const val MAX_RIDE_MS = 4 * 60 * 60_000L
        /** Memória salva há mais que isto não é restaurada. */
        const val MAX_RESTORE_MS = 3 * 60 * 60_000L

        /** Duração prevista desconhecida: corrida aberta há menos de ¾ disto foi cancelada. */
        private const val UNKNOWN_EXPECTED_MS = 20 * 60_000L

        /**
         * Mesmo passageiro? Nomes já em minúsculas e sem acento; um pode ser o começo do outro
         * (OCR que cola o sobrenome: "caioaugusto" = "caio") ou ter uma letra lida errada em nome
         * de 4+ letras ("calo" = "caio").
         */
        fun sameName(a: String, b: String): Boolean {
            if (a.length < 2 || b.length < 2) return false
            if (a.startsWith(b) || b.startsWith(a)) return true
            return a.length >= 4 && b.length >= 4 && oneEditApart(a, b)
        }

        private fun oneEditApart(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (b.length - j) <= 1
        }
    }
}
