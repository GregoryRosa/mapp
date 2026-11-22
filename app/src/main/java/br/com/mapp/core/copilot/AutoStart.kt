package br.com.mapp.core.copilot

import br.com.mapp.core.model.Platform
import java.text.Normalizer

/** Motorista online ou offline, lido na tela inicial do app de corrida. */
enum class OnlineState { ONLINE, OFFLINE }

/** Leitura da tela inicial: [platform] com o motorista [state] (publicado pelo leitor no `OfferBus`). */
data class RideOnline(val platform: Platform, val state: OnlineState)

/**
 * Online/offline nos textos da tela (árvore de Acessibilidade). A Uber entrega a tela inicial como texto
 * ("Página inicial", "R$ 0,00", "Você está online") — só o cartão da oferta é que não vem.
 * 99 e inDrive ainda sem print da tela inicial: null.
 */
object OnlineScreen {

    private val UBER_ONLINE = listOf("voce esta online", "you're online", "you are online", "procurando viagens", "finding trips")
    private val UBER_OFFLINE = listOf("voce esta offline", "you're offline", "you are offline")

    fun detect(platform: Platform, texts: List<String>): OnlineState? {
        if (platform != Platform.UBER) return null
        var online = false
        var offline = false
        for (text in texts) {
            val t = normalize(text)
            if (UBER_ONLINE.any { t.contains(it) }) online = true
            if (UBER_OFFLINE.any { t.contains(it) }) offline = true
        }
        return when {
            online && !offline -> OnlineState.ONLINE
            offline && !online -> OnlineState.OFFLINE
            else -> null // os dois na tela (transição) ou nenhum: não decide nada
        }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
}

enum class AutoStartAction {
    /** Ligar o copiloto agora. */
    START,
    /** Motorista ficou offline: a pausa do "parei à mão" acaba (próximo online liga de novo). */
    WAKE,
    NONE,
}

/**
 * Copiloto automático: a Uber aberta com o motorista online liga o copiloto sozinho.
 *
 * Parar o copiloto à mão (segurar o "M", "Parar" na notificação ou na tela inicial) **pausa** o
 * automático: ele só volta a ligar depois que o motorista fica offline e online de novo — senão o
 * copiloto voltaria na hora, com a Uber ainda mostrando "Você está online". A pausa também acaba em
 * [SNOOZE_MS] (Uber fechada online, sem a tela de offline) ou ao ligar o copiloto à mão.
 */
object AutoStart {

    /** Pausa do "parei à mão" vale no máximo isto (dia seguinte liga normalmente). */
    const val SNOOZE_MS = 12 * 60 * 60_000L

    /** Tentativa que não ligou (assinatura, Android recusou): só tenta de novo depois disto. */
    const val RETRY_MS = 30_000L

    /**
     * @param snoozedAt quando o copiloto foi parado à mão (0 = sem pausa).
     * @param lastTryAt última tentativa de ligar sozinho (0 = nenhuma).
     */
    fun decide(
        enabled: Boolean,
        state: OnlineState,
        copilotRunning: Boolean,
        snoozedAt: Long,
        lastTryAt: Long,
        now: Long,
    ): AutoStartAction = when {
        state == OnlineState.OFFLINE -> if (snoozedAt > 0L) AutoStartAction.WAKE else AutoStartAction.NONE
        !enabled || copilotRunning -> AutoStartAction.NONE
        snoozed(snoozedAt, now) -> AutoStartAction.NONE
        lastTryAt > 0L && now - lastTryAt in 0 until RETRY_MS -> AutoStartAction.NONE
        else -> AutoStartAction.START
    }

    fun snoozed(snoozedAt: Long, now: Long): Boolean = snoozedAt > 0L && now - snoozedAt in 0 until SNOOZE_MS
}
