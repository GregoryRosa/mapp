package br.com.mapp.core.shift

import kotlin.math.max
import kotlin.math.min

/**
 * Onde a jornada estava quando o motorista ficou offline (sem corrida aberta): [since] = horário,
 * [km] = km do GPS naquele momento, [shiftStartedAt] = qual jornada.
 */
data class OfflineMark(val since: Long, val km: Double, val shiftStartedAt: Long)

/** O que fazer com a marca de offline a cada conferência. */
sealed interface OfflineStep {
    /** Continua offline, ainda dentro do prazo. */
    data object Wait : OfflineStep
    /** A marca não vale mais (jornada pausada/encerrada/trocada, retomada à mão ou pausa desligada). */
    data object Drop : OfflineStep
    /** Teve corrida depois da marca (ou há corrida aberta): o prazo recomeça agora. */
    data class Restart(val mark: OfflineMark) : OfflineStep
    /** Passou do prazo: a jornada pausa no momento em que ficou offline. */
    data class Pause(val shift: Shift) : OfflineStep
}

/**
 * Pausa automática da jornada (pedido do dono, 27/11/2026): **ficou offline na Uber e passou de X
 * minutos** → a jornada pausa sozinha, **desde o momento em que ficou offline** (tempo e km do GPS
 * voltam para aquele ponto: o caminho de volta para casa não entra nos números do dia).
 *
 * Só a troca online → offline marca (ficar offline com a jornada já pausada, ou dar ▶ à mão estando
 * offline, não pausa de novo). Oferta lida, corrida aberta ou corrida contada depois da marca
 * recomeçam o prazo (motorista rodando na 99/inDrive com a Uber offline). Ao ficar online de novo, a
 * jornada pausada por isso volta sozinha ([resumeOnOnline]); pausa à mão continua parada.
 */
object OfflinePause {

    const val OFF = 0
    const val DEFAULT_MINUTES = 10

    /** Opções da tela inicial (minutos; [OFF] = desligado). */
    val CHOICES = listOf(OFF, 5, 10, 15, 20, 30, 45, 60)

    fun label(minutes: Int): String = if (minutes <= OFF) "Desligado" else "$minutes min"

    /** Prazo válido (valor salvo fora da lista vira o padrão). */
    fun sanitize(minutes: Int): Int = if (minutes in CHOICES) minutes else DEFAULT_MINUTES

    /** Ficou offline agora: marca, se a jornada está rodando (pausada/sem jornada = nada a pausar). */
    fun mark(shift: Shift?, now: Long): OfflineMark? =
        shift?.takeUnless { it.paused }?.let { OfflineMark(now, it.gpsKm, it.startedAt) }

    /**
     * Conferência (a cada leitura offline e a cada ~15 s com o copiloto ligado).
     * @param rideOpen corrida aceita e ainda aberta no MApp (na jornada ou no acompanhamento do aceite).
     */
    fun step(shift: Shift?, mark: OfflineMark, minutes: Int, rideOpen: Boolean, now: Long): OfflineStep {
        if (minutes <= OFF) return OfflineStep.Drop
        if (shift == null || shift.paused || shift.startedAt != mark.shiftStartedAt) return OfflineStep.Drop
        // Deu ▶ depois de ficar offline (pausou e retomou à mão): vale a decisão do motorista.
        if ((shift.resumedAt ?: 0L) > mark.since) return OfflineStep.Drop
        if (rideOpen || shift.trips.any { it.atMillis >= mark.since }) {
            return OfflineStep.Restart(OfflineMark(now, shift.gpsKm, shift.startedAt))
        }
        if (now - mark.since < minutes * 60_000L) return OfflineStep.Wait
        return OfflineStep.Pause(pausedAt(shift, mark))
    }

    /** A jornada pausada no momento da marca: tempo até ali e km do GPS de volta ao daquele momento. */
    fun pausedAt(shift: Shift, mark: OfflineMark): Shift {
        val at = max(mark.since, shift.resumedAt ?: mark.since)
        return shift.copy(
            activeMs = shift.elapsedMs(at),
            resumedAt = null,
            gpsKm = min(shift.gpsKm, mark.km),
        )
    }

    /** Ficou online de novo: retoma só a jornada que esta regra pausou ([pausedShift] = `startedAt` dela). */
    fun resumeOnOnline(shift: Shift?, pausedShift: Long): Boolean =
        pausedShift > 0L && shift != null && shift.paused && shift.startedAt == pausedShift
}
