package br.com.mapp.core.shift

/**
 * Corridas aceitas ainda em andamento, para o menu **Atividades** do ícone "M": a **atual** (a mais
 * antiga aberta — indo buscar ou em viagem) e a **próxima** (aceita durante a atual, na fila).
 * Guarda os dados do aceite ([data]: oferta, tipo…), os mesmos da notificação da corrida aceita.
 *
 * Quem diz quantas continuam abertas é o acompanhamento (corridas abertas no [TripMatcher] e
 * corridas em andamento da jornada): as corridas terminam da mais antiga para a mais nova, então as
 * abertas são sempre as N aceitas mais recentes — [current] corta as mais velhas que já acabaram.
 * Corrida cancelada/desfeita sai na hora ([removed]).
 */
class ActiveRides<K : Any, T : Any>(
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val max: Int = MAX,
) {
    data class Ride<K, T>(
        val key: K,
        val data: T,
        val acceptedAt: Long,
        /** 1º nome lido na tela de aceite (null no +1). */
        val passenger: String?,
        /** Aceite visto só no embarque (a chegada prevista conta só a viagem). */
        val onBoard: Boolean,
    )

    private val rides = ArrayList<Ride<K, T>>()

    val size: Int get() = rides.size

    /** Corrida aceita. A mesma oferta de novo só atualiza os dados (mantém a hora do aceite). */
    fun accepted(key: K, data: T, at: Long, passenger: String? = null, onBoard: Boolean = false) {
        val i = rides.indexOfFirst { it.key == key }
        if (i >= 0) {
            val old = rides[i]
            rides[i] = old.copy(data = data, passenger = passenger ?: old.passenger)
            return
        }
        rides += Ride(key, data, at, passenger, onBoard)
        rides.sortBy { it.acceptedAt }
        while (rides.size > max) rides.removeAt(0)
    }

    /** A oferta chegou completada (endereço localizado, tipo lido depois). false = não é corrida aberta. */
    fun updated(key: K, data: T): Boolean {
        val i = rides.indexOfFirst { it.key == key }
        if (i < 0 || rides[i].data == data) return false
        rides[i] = rides[i].copy(data = data)
        return true
    }

    fun data(key: K): T? = rides.firstOrNull { it.key == key }?.data

    /** Corrida cancelada ou desfeita. */
    fun removed(key: K): Boolean = rides.removeAll { it.key == key }

    fun clear() = rides.clear()

    /**
     * Atual e próxima com [open] corridas abertas agora: mantém só as [open] aceitas mais recentes
     * (as mais velhas já terminaram) e nenhuma com mais de [maxAgeMs].
     */
    fun current(open: Int, now: Long): Pair<Ride<K, T>?, Ride<K, T>?> {
        rides.removeAll { now - it.acceptedAt > maxAgeMs }
        while (rides.size > open.coerceAtLeast(0)) rides.removeAt(0)
        return rides.getOrNull(0) to rides.getOrNull(1)
    }

    companion object {
        /** Corrida aberta há mais que isso já acabou (o acompanhamento perdeu o fim). */
        const val MAX_AGE_MS = 3 * 60 * 60_000L
        const val MAX = 6
    }
}
