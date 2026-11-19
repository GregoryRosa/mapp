package br.com.mapp.core.shift

/**
 * **Encerrar corrida à mão** — seção do painel ⚙ do ícone "M".
 *
 * Serve para dois casos em que o copiloto fica com uma corrida aberta que não vai terminar sozinha:
 * o **+1 tocado sem querer** no Card (corrida que não está acontecendo) e a corrida de verdade cujo
 * fim o leitor não viu (Waze na frente a viagem toda, serviço derrubado pela bateria...).
 *
 * Kotlin puro: olha a jornada e quantas corridas o acompanhamento do aceite
 * ([TripMatcher.openRides]) vê abertas — a corrida pode estar nos dois, só na jornada (contada pelo
 * +1) ou só no acompanhamento (aceita com a jornada fechada, quando o "M" fica verde sem jornada).
 *
 * Só mexe nos números do MApp: nunca toca no app de corrida.
 */
object ManualRide {

    /** O que o painel mostra e qual corrida cada botão fecha. */
    data class State(
        /** Corridas que o MApp vê em andamento agora. */
        val open: Int,
        /** A que o ✓ finaliza: a aberta mais ANTIGA da jornada (as corridas terminam nessa ordem). */
        val oldest: ShiftTrip?,
        /** A que o ✕ tira: a aberta mais RECENTE da jornada (o +1 sem querer é a última). */
        val newest: ShiftTrip?,
        /** Jornada aberta agora. */
        val hasShift: Boolean,
    ) {
        /** Tem o que encerrar. */
        val any: Boolean get() = open > 0

        /** Corrida que só o acompanhamento vê: não está na jornada, então não há valor a somar. */
        val outsideShift: Boolean get() = open > 0 && oldest == null

        /** Corridas abertas além da que os botões fecham. */
        val queued: Int get() = (open - 1).coerceAtLeast(0)
    }

    fun state(shift: Shift?, trackedOpen: Int): State {
        val pending = shift?.trips?.filter { !it.finished }.orEmpty()
        return State(
            open = maxOf(pending.size, trackedOpen.coerceAtLeast(0)),
            oldest = pending.firstOrNull(),
            newest = pending.lastOrNull(),
            hasShift = shift != null,
        )
    }

    /** "UberX · R$ 23,40" (sem o tipo lido, o nome do app: "Uber · R$ 23,40"). */
    fun label(trip: ShiftTrip?): String? = trip?.let {
        (ShiftTrip.categoryName(it.category) ?: it.platform.label) + " · " + ShiftMath.money(it.price)
    }
}
