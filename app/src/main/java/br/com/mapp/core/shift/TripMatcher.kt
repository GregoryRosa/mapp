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
 * corrida nova** → conta a última oferta vista (se ainda não contada). Oferta vista durante uma
 * viagem vale por até [queuedMs]; fora de viagem, por [freshMs] (a tela de oferta some ao aceitar).
 *
 * Etapas (Uber): "Encontro com" = aceita, indo buscar (ainda pode ser cancelada); "Destino de" =
 * passageiro embarcou, viagem confirmada; "Como foi a viagem?" = terminou; "Procurando viagens"
 * ([TripStage.IDLE]) = sem corrida.
 *
 * Telas que passaram rápido demais (aceite/fim muito rápidos, Waze na frente) são recuperadas depois:
 * - **aceite não lido**: o passageiro novo só aparece no embarque ou na volta para a Uber → conta a
 *   última oferta vista até [lateMs] antes, se depois dela a tela inicial não apareceu (oferta
 *   recusada) e se a distância da tela combina com a oferta;
 * - **fim não lido**: outro passageiro na tela, ou a tela inicial "Procurando viagens" → as corridas
 *   abertas antes dele terminaram. Quem embarcou finaliza; quem não embarcou finaliza se já passou
 *   tempo para a corrida inteira (¾ do previsto), senão foi cancelada. Tela inicial menos de
 *   [settleMs] depois do aceite não fecha nada (pode ser só a transição da Uber).
 *
 * [K] = identidade da oferta (o overlay usa a RawOffer sem endereços/tipo).
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
    )

    private enum class Ending { FINISHED, CANCELLED, WAIT }

    private var offer: K? = null
    private var offerSeenAt = 0L
    private var offerDuringTrip = false
    private var offerDeclined = false
    private var offerPickupKm: Double? = null
    private var offerTripKm: Double? = null
    private var offerExpectedMs: Long? = null
    private var offerPlatform: Platform? = null
    private var onTrip = false
    private val counted = LinkedHashSet<K>()
    /** Corridas abertas, da mais antiga para a mais nova. */
    private val riders = ArrayDeque<Rider>()

    /** Corridas abertas que estão na jornada. */
    val openRides: Int get() = riders.count { it.counted }

    /**
     * Oferta no cartão agora (chamar a cada atualização do cartão). [pickupKm]/[tripKm] conferem a
     * distância das telas seguintes; [expectedMs] = duração prevista (busca + viagem).
     */
    fun offerSeen(
        key: K,
        now: Long,
        pickupKm: Double? = null,
        tripKm: Double? = null,
        expectedMs: Long? = null,
        platform: Platform? = null,
    ) {
        if (key != offer) {
            offer = key
            offerDuringTrip = onTrip
            offerDeclined = false
        }
        offerSeenAt = now
        offerPickupKm = pickupKm
        offerTripKm = tripKm
        offerExpectedMs = expectedMs
        offerPlatform = platform
    }

    /** Oferta contada (automático): não conta de novo. */
    fun markCounted(key: K) {
        counted.remove(key)
        counted.add(key)
        while (counted.size > MAX_KEYS) counted.remove(counted.first())
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
            riders.addLast(Rider(null, false, now, expectedFor(key), counted = true, platform = platformFor(key)))
            trim()
        }
    }

    /** Corrida desfeita/cancelada: a oferta pode ser contada de novo. */
    fun forget(key: K) {
        counted.remove(key)
    }

    /** Finalizada à mão (✓ no painel): sai a corrida aberta mais antiga. */
    fun closedOldest() {
        riders.firstOrNull { it.counted }?.let { riders.remove(it) }
    }

    /** Cancelada ou desfeita à mão (✕ / ↶): sai a corrida aberta mais recente. */
    fun closedNewest() {
        riders.lastOrNull { it.counted }?.let { riders.remove(it) }
    }

    fun reset() {
        offer = null
        onTrip = false
        offerDuringTrip = false
        offerDeclined = false
        counted.clear()
        riders.clear()
    }

    fun onStage(event: TripEvent, now: Long): Result<K> = when (event.stage) {
        TripStage.FINISHED -> finished(event.platform)
        TripStage.IDLE -> idle(event.platform, now)
        TripStage.ACCEPTED, TripStage.ON_TRIP -> active(event, now)
    }.copy(platform = event.platform)

    private fun finished(platform: Platform?): Result<K> {
        // Quem terminou: o mais antigo daquele app que já embarcou (ou o mais antigo, se o embarque não foi lido).
        val here = riders.filter { platform == null || it.platform == null || it.platform == platform }
        val done = here.firstOrNull { it.started } ?: here.firstOrNull()
        if (done != null) riders.remove(done)
        onTrip = false
        return Result(null, newRide = false)
    }

    /** Tela inicial: nenhuma corrida em andamento NESTE app ([platform]); as dos outros apps seguem. */
    private fun idle(platform: Platform?, now: Long): Result<K> {
        fun here(p: Platform?) = platform == null || p == null || p == platform
        // Oferta que não virou corrida e o app voltou a procurar: foi recusada ou expirou.
        offer?.let { if (it !in counted && here(offerPlatform)) offerDeclined = true }
        var finished = 0
        var cancelled = false
        for (r in riders.toList().filter { here(it.platform) }) {
            when (ending(r, now, wait = true)) {
                Ending.FINISHED -> { riders.remove(r); if (r.counted) finished++ }
                Ending.CANCELLED -> { riders.remove(r); if (r.counted) cancelled = true }
                Ending.WAIT -> Unit
            }
        }
        onTrip = riders.isNotEmpty()
        return Result(null, newRide = false, cancelledPickup = cancelled, finishedUnseen = finished)
    }

    private fun active(event: TripEvent, now: Long): Result<K> {
        onTrip = true
        val boarding = event.stage == TripStage.ON_TRIP
        val name = event.passenger
        if (name == null) {
            if (boarding) {
                riders.firstOrNull { !it.started }?.started = true
                return Result(null, newRide = false)
            }
            // Aceite sem nome (OCR cortou): é a busca já aberta, ou corrida nova só com oferta recente.
            if (riders.any { !it.started }) return Result(null, newRide = false)
            val k = candidate(now, named = false, event, boarding = false)
            riders.addLast(Rider(null, false, now, expectedFor(k), counted = k != null, platform = event.platform))
            trim()
            return Result(k, newRide = true)
        }
        val known = riders.firstOrNull { r -> r.name?.let { sameName(it, name) } == true }
            // Corrida aberta sem nome (aceite lido sem nome ou +1 manual): o nome que apareceu é dela.
            ?: riders.firstOrNull { it.name == null && (boarding || !it.started) }?.also { it.name = name }
        if (known != null) {
            val started = boarding && !known.started
            if (started) known.started = true
            val closed = closeBefore(known, now)
            return Result(null, newRide = false, started = started, cancelledPickup = closed.second, finishedUnseen = closed.first)
        }
        val k = candidate(now, named = true, event, boarding = boarding)
        val rider = Rider(name, boarding, now, expectedFor(k), counted = k != null, platform = event.platform)
        riders.addLast(rider)
        val closed = closeBefore(rider, now)
        trim()
        return Result(
            k,
            newRide = true,
            cancelledPickup = closed.second,
            started = boarding,
            finishedUnseen = closed.first,
        )
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
            riders.remove(r)
            when (ending(r, now, wait = false)) {
                Ending.FINISHED -> if (r.counted) finished++
                else -> if (r.counted) cancelled = true
            }
        }
        return finished to cancelled
    }

    /** Corrida aberta que saiu da tela: terminou ou foi cancelada? */
    private fun ending(r: Rider, now: Long, wait: Boolean): Ending {
        if (r.started) return Ending.FINISHED
        val elapsed = now - r.since
        if (wait && elapsed < settleMs) return Ending.WAIT
        val full = r.expectedMs ?: UNKNOWN_EXPECTED_MS
        return if (elapsed * 4 >= full * 3) Ending.FINISHED else Ending.CANCELLED
    }

    private fun candidate(now: Long, named: Boolean, event: TripEvent, boarding: Boolean): K? {
        val k = offer ?: return null
        if (k in counted) return null
        // Tela de um app, oferta de outro (Uber + 99 ligados juntos): não é ela.
        if (event.platform != null && offerPlatform != null && event.platform != offerPlatform) return null
        val screenKm = event.km
        val age = now - offerSeenAt
        if (age <= freshMs) return k
        if (!named) return null
        if (offerDuringTrip && age <= queuedMs) return k
        // Aceite não lido: o passageiro aparece só minutos depois (embarque ou volta do Waze).
        if (!offerDeclined && age <= lateMs && plausible(screenKm, boarding)) return k
        return null
    }

    /** Distância que a tela mostra cabe na oferta? (folga para desvio/trânsito; sem dado = cabe). */
    private fun plausible(screenKm: Double?, boarding: Boolean): Boolean {
        val leg = if (boarding) offerTripKm else offerPickupKm
        if (screenKm == null || leg == null) return true
        return screenKm <= leg * 1.5 + 1.0
    }

    private fun expectedFor(key: K?): Long? = if (key != null && key == offer) offerExpectedMs else null

    private fun platformFor(key: K?): Platform? = if (key != null && key == offer) offerPlatform else null

    private fun trim() {
        while (riders.size > MAX_RIDERS) riders.removeFirst()
    }

    companion object {
        private const val MAX_KEYS = 50
        private const val MAX_RIDERS = 4

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
