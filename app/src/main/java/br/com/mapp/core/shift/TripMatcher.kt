package br.com.mapp.core.shift

import br.com.mapp.core.model.TripEvent
import br.com.mapp.core.model.TripStage

/**
 * Decide qual oferta foi aceita a partir das telas depois da oferta (Kotlin puro).
 *
 * Problema que resolve: aceitando uma corrida **durante outra viagem**, a Uber continua mostrando
 * "Destino de <passageiro atual>"; o "Encontro com <novo>" só aparece quando a viagem atual termina,
 * muitos minutos depois da oferta. Por isso o nome do passageiro é a prova: **nome novo na tela =
 * corrida nova** → conta a última oferta vista (se ainda não contada). Oferta vista durante uma
 * viagem vale por até [queuedMs]; fora de viagem, só por [freshMs] (a tela de oferta some ao aceitar).
 *
 * Etapas (Uber): "Encontro com" = aceita, indo buscar (ainda pode ser cancelada); "Destino de" =
 * passageiro embarcou, viagem confirmada; "Como foi a viagem?" = terminou. Um "Encontro com <outro>"
 * enquanto a busca anterior nem começou (sem "Destino de") = a busca anterior foi cancelada.
 *
 * [K] = identidade da oferta (o overlay usa a RawOffer sem endereços/tipo).
 */
class TripMatcher<K : Any>(
    private val freshMs: Long = 90_000L,
    private val queuedMs: Long = 60 * 60_000L,
) {
    data class Result<K>(
        /** Oferta a contar como corrida aceita (null = nada a contar). */
        val count: K?,
        /** A tela indica corrida nova (passageiro novo ou aceite sem nome), com ou sem oferta para contar. */
        val newRide: Boolean,
        /** A busca anterior (aceita, sem embarque) foi cancelada: tirar a corrida em andamento dela. */
        val cancelledPickup: Boolean = false,
        /** Passageiro embarcou agora ("Destino de"): viagem confirmada. */
        val started: Boolean = false,
    )

    private data class Rider(val name: String, var started: Boolean)

    private var offer: K? = null
    private var offerSeenAt = 0L
    private var offerDuringTrip = false
    private var onTrip = false
    private val counted = LinkedHashSet<K>()
    /** Passageiros das corridas ainda abertas (o mais antigo sai a cada "Como foi a viagem?"). */
    private val riders = ArrayDeque<Rider>()

    /** Oferta no cartão agora (chamar a cada atualização do cartão). */
    fun offerSeen(key: K, now: Long) {
        if (key != offer) {
            offer = key
            offerDuringTrip = onTrip
        }
        offerSeenAt = now
    }

    /** Oferta contada (automático ou +1 manual): não conta de novo. */
    fun markCounted(key: K) {
        counted.remove(key)
        counted.add(key)
        while (counted.size > MAX_KEYS) counted.remove(counted.first())
    }

    /** Corrida desfeita/cancelada: a oferta pode ser contada de novo. */
    fun forget(key: K) {
        counted.remove(key)
    }

    fun reset() {
        offer = null
        onTrip = false
        offerDuringTrip = false
        counted.clear()
        riders.clear()
    }

    fun onStage(event: TripEvent, now: Long): Result<K> {
        if (event.stage == TripStage.FINISHED) {
            onTrip = false
            // Quem terminou: o mais antigo que já embarcou (ou o mais antigo, se o embarque não foi lido).
            val done = riders.firstOrNull { it.started } ?: riders.firstOrNull()
            if (done != null) riders.remove(done)
            return Result(null, newRide = false)
        }
        onTrip = true
        val boarding = event.stage == TripStage.ON_TRIP
        val name = event.passenger
        if (name == null) {
            // Sem nome (OCR cortou): só o aceite com oferta recente, como antes.
            if (boarding) return Result(null, newRide = false)
            return Result(candidate(now, allowQueued = false), newRide = true)
        }
        val known = riders.firstOrNull { sameName(it.name, name) }
        if (known != null) {
            val started = boarding && !known.started
            if (started) known.started = true
            return Result(null, newRide = false, started = started)
        }
        // Passageiro novo indo ser buscado enquanto outra busca nem começou → aquela foi cancelada.
        val cancelled = if (!boarding) riders.firstOrNull { !it.started } else null
        if (cancelled != null) riders.remove(cancelled)
        riders.addLast(Rider(name, started = boarding))
        while (riders.size > MAX_RIDERS) riders.removeFirst()
        return Result(
            candidate(now, allowQueued = true),
            newRide = true,
            cancelledPickup = cancelled != null,
            started = boarding,
        )
    }

    private fun candidate(now: Long, allowQueued: Boolean): K? {
        val k = offer ?: return null
        if (k in counted) return null
        val age = now - offerSeenAt
        val ok = age <= freshMs || (allowQueued && offerDuringTrip && age <= queuedMs)
        return if (ok) k else null
    }

    companion object {
        private const val MAX_KEYS = 50
        private const val MAX_RIDERS = 3

        /**
         * Mesmo passageiro? Nomes já em minúsculas e sem acento; um pode ser o começo do outro
         * (OCR que cola o sobrenome: "caioaugusto" = "caio").
         */
        fun sameName(a: String, b: String): Boolean =
            a.length >= 2 && b.length >= 2 && (a.startsWith(b) || b.startsWith(a))
    }
}
