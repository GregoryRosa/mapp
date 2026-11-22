package br.com.mapp.core

import br.com.mapp.core.copilot.AutoStart
import br.com.mapp.core.copilot.AutoStartAction
import br.com.mapp.core.copilot.OnlineScreen
import br.com.mapp.core.copilot.OnlineState
import br.com.mapp.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStartTest {

    // Texto da tela inicial da Uber como chega pela Acessibilidade (print de 22/11/2026 e diagnóstico de 12/11).
    private val uberOnline = listOf(
        "Página inicial", "R$ 0,00", "HOJE", "+R$ 15", "+R$ 9,25",
        "Área de espera do motorista parceiro", "1-3 min", "1 min", "Você está online",
    )
    private val uberOffline = listOf("Página inicial", "R$ 0,00", "HOJE", "Você está offline", "Ficar online")

    @Test fun tela_inicial_da_uber_online() {
        assertEquals(OnlineState.ONLINE, OnlineScreen.detect(Platform.UBER, uberOnline))
    }

    @Test fun tela_inicial_da_uber_offline() {
        // "Ficar online" (botão) não conta como online.
        assertEquals(OnlineState.OFFLINE, OnlineScreen.detect(Platform.UBER, uberOffline))
    }

    @Test fun variacoes_de_texto() {
        assertEquals(OnlineState.ONLINE, OnlineScreen.detect(Platform.UBER, listOf("VOCÊ ESTÁ ONLINE")))
        assertEquals(OnlineState.ONLINE, OnlineScreen.detect(Platform.UBER, listOf("Voce esta  online")))
        assertEquals(OnlineState.ONLINE, OnlineScreen.detect(Platform.UBER, listOf("You’re online")))
        assertEquals(OnlineState.ONLINE, OnlineScreen.detect(Platform.UBER, listOf("Procurando viagens")))
        assertEquals(OnlineState.OFFLINE, OnlineScreen.detect(Platform.UBER, listOf("You're offline")))
    }

    @Test fun sem_frase_ou_com_as_duas_nao_decide() {
        assertNull(OnlineScreen.detect(Platform.UBER, listOf("Encontro com Caio", "2 min", "0,8 km")))
        assertNull(OnlineScreen.detect(Platform.UBER, listOf("Você está offline", "Você está online")))
        assertNull(OnlineScreen.detect(Platform.UBER, emptyList()))
    }

    @Test fun outros_apps_ainda_nao_calibrados() {
        assertNull(OnlineScreen.detect(Platform.NINETY_NINE, uberOnline))
        assertNull(OnlineScreen.detect(Platform.INDRIVE, uberOnline))
    }

    private fun decide(
        state: OnlineState = OnlineState.ONLINE,
        enabled: Boolean = true,
        running: Boolean = false,
        snoozedAt: Long = 0L,
        lastTryAt: Long = 0L,
        now: Long = NOW,
    ) = AutoStart.decide(enabled, state, running, snoozedAt, lastTryAt, now)

    @Test fun uber_online_liga_o_copiloto() {
        assertEquals(AutoStartAction.START, decide())
    }

    @Test fun ja_ligado_ou_desligado_nos_ajustes_nao_faz_nada() {
        assertEquals(AutoStartAction.NONE, decide(running = true))
        assertEquals(AutoStartAction.NONE, decide(enabled = false))
    }

    @Test fun offline_nao_liga() {
        assertEquals(AutoStartAction.NONE, decide(state = OnlineState.OFFLINE))
    }

    @Test fun parado_a_mao_so_volta_depois_de_ficar_offline_e_online() {
        val stoppedAt = NOW - 60_000L
        // Uber continua "Você está online" logo depois de parar: não religa.
        assertEquals(AutoStartAction.NONE, decide(snoozedAt = stoppedAt))
        // Ficou offline: a pausa acaba...
        assertEquals(AutoStartAction.WAKE, decide(state = OnlineState.OFFLINE, snoozedAt = stoppedAt))
        // ...e ao ficar online de novo (pausa zerada) liga.
        assertEquals(AutoStartAction.START, decide(snoozedAt = 0L))
    }

    @Test fun pausa_vale_no_maximo_12_horas() {
        assertEquals(AutoStartAction.NONE, decide(snoozedAt = NOW - AutoStart.SNOOZE_MS + 1))
        assertEquals(AutoStartAction.START, decide(snoozedAt = NOW - AutoStart.SNOOZE_MS))
    }

    @Test fun tentativa_que_nao_ligou_espera_antes_de_tentar_de_novo() {
        assertEquals(AutoStartAction.NONE, decide(lastTryAt = NOW - 5_000L))
        assertEquals(AutoStartAction.START, decide(lastTryAt = NOW - AutoStart.RETRY_MS))
    }

    @Test fun relogio_voltando_nao_trava_o_automatico() {
        assertEquals(AutoStartAction.START, decide(lastTryAt = NOW + 60_000L))
    }

    private companion object {
        const val NOW = 1_795_000_000_000L
    }
}
