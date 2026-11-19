package br.com.mapp.core

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.nav.HoldPlan
import br.com.mapp.core.nav.NavApp
import br.com.mapp.core.nav.NavHold
import br.com.mapp.core.nav.NavLinkState
import br.com.mapp.core.nav.ReleaseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHoldTest {

    private val own = "br.com.mapp"
    private val uber = "com.ubercab.driver"

    @Test fun le_o_link_de_navegacao_da_uber() {
        val r = NavHold.parse("waze://?ll=-23.550520,-46.633308&navigate=yes")!!
        assertEquals(-23.550520, r.lat!!, 1e-9)
        assertEquals(-46.633308, r.lon!!, 1e-9)
        assertTrue(r.navigate)
        assertFalse(r.test)
        assertEquals("ll=-23.550520,-46.633308&navigate=yes", r.rawQuery)
    }

    @Test fun le_variantes_do_link() {
        val ul = NavHold.parse("waze://ul?ll=-23.5%2C-46.6&navigate=true")!!
        assertEquals(-23.5, ul.lat!!, 1e-9)
        assertTrue(ul.navigate)
        val web = NavHold.parse("https://www.waze.com/ul?q=Pra%C3%A7a+da+S%C3%A9&navigate=yes")!!
        assertEquals("Praça da Sé", web.query)
        assertNull(web.lat)
        val bare = NavHold.parse("waze://")!!
        assertNull(bare.lat)
        assertFalse(bare.navigate)
        assertEquals("", bare.rawQuery)
    }

    @Test fun nao_e_link_de_navegacao() {
        assertNull(NavHold.parse(null))
        assertNull(NavHold.parse("https://maps.google.com/?q=1,2"))
        assertNull(NavHold.parse("https://example.com/?ll=1,2"))
    }

    @Test fun le_os_links_do_google_maps() {
        val nav = NavHold.parse("google.navigation:q=-23.550520,-46.633308&mode=d")!!
        assertEquals(NavApp.GOOGLE_MAPS, nav.origin)
        assertEquals(-23.550520, nav.lat!!, 1e-9)
        assertEquals(-46.633308, nav.lon!!, 1e-9)
        assertTrue(nav.navigate)
        assertEquals("google.navigation:q=-23.550520,-46.633308&mode=d", nav.link)
        val address = NavHold.parse("google.navigation:?q=Pra%C3%A7a+da+S%C3%A9")!!
        assertEquals("Praça da Sé", address.query)
        assertNull(address.lat)
        assertEquals(-23.5, NavHold.parse("google.navigation:ll=-23.5,-46.6")!!.lat!!, 1e-9)
        // geo: mostra o ponto (não navega).
        val geo = NavHold.parse("geo:-23.5,-46.6")!!
        assertEquals(NavApp.GOOGLE_MAPS, geo.origin)
        assertEquals(-46.6, geo.lon!!, 1e-9)
        assertFalse(geo.navigate)
        // Ponto marcado com nome: vale o do q.
        val marked = NavHold.parse("geo:0,0?q=-23.55,-46.63(Pra%C3%A7a%20da%20S%C3%A9)")!!
        assertEquals(-23.55, marked.lat!!, 1e-9)
        assertNull(marked.query)
        // Endereço procurado perto de um ponto: o destino é o endereço.
        val near = NavHold.parse("geo:-23.5,-46.6?q=Rua+Augusta+100")!!
        assertEquals("Rua Augusta 100", near.query)
        assertNull(near.lat)
        // geo:0,0 sem q = sem destino.
        assertNull(NavHold.parse("geo:0,0")!!.lat)
        assertTrue(NavHold.parse(NavHold.MAPS_TEST_URI)!!.test)
        assertEquals(NavApp.GOOGLE_MAPS, NavHold.parse(NavHold.MAPS_TEST_URI)!!.origin)
    }

    @Test fun link_do_google_maps_vai_para_o_app_escolhido() {
        val nav = NavHold.parse("google.navigation:q=-23.55052,-46.63331&mode=d")!!
        // Google Maps escolhido: o link segue como chegou.
        assertEquals("google.navigation:q=-23.55052,-46.63331&mode=d", NavHold.target(nav, NavApp.GOOGLE_MAPS))
        // Waze escolhido: mesmo destino, já navegando.
        assertEquals("waze://?ll=-23.550520,-46.633310&navigate=yes", NavHold.target(nav, NavApp.WAZE))
        assertEquals("waze://?ll=-23.500000,-46.600000", NavHold.target(NavHold.parse("geo:-23.5,-46.6")!!, NavApp.WAZE))
        assertEquals(
            "waze://?q=Pra%C3%A7a%20da%20S%C3%A9&navigate=yes",
            NavHold.target(NavHold.parse("google.navigation:q=Pra%C3%A7a+da+S%C3%A9")!!, NavApp.WAZE),
        )
        assertEquals("https://waze.com/ul?ll=-23.550520,-46.633310&navigate=yes", NavHold.webUrl(nav))
        // Sem destino (botão Testar): só abre o app.
        assertNull(NavHold.target(NavHold.parse(NavHold.MAPS_TEST_URI)!!, NavApp.WAZE))
        assertNull(NavHold.target(NavHold.parse(NavHold.MAPS_TEST_URI)!!, NavApp.GOOGLE_MAPS))
        assertEquals(
            "🧭 Google Maps pedido por Uber: -23.55052,-46.63331 · navegar · segurando até 2 s · rota no Waze",
            NavHold.describe(nav, uber, HoldPlan(2_000, ""), NavApp.WAZE),
        )
        assertEquals(
            "🧭 Google Maps pedido por Uber: -23.55052,-46.63331 · navegar · segurando até 2 s",
            NavHold.describe(nav, uber, HoldPlan(2_000, "")),
        )
    }

    @Test fun pedido_de_outro_app_vai_para_o_app_do_link() {
        val geo = NavHold.parse("geo:-23.5,-46.6")!!
        val both = setOf(NavApp.WAZE, NavApp.GOOGLE_MAPS)
        // Endereço aberto pelo WhatsApp: vai para o Google Maps, mesmo com o Waze escolhido — e mesmo com o copiloto parado.
        val other = NavHold.plan(true, 2_000, false, true, "com.whatsapp", own, false)
        assertTrue(other.passThrough)
        assertEquals("pedido de outro app", other.reason)
        assertEquals(NavApp.GOOGLE_MAPS, NavHold.chooseApp(geo, other, NavApp.WAZE, both))
        // Pedido da Uber: o escolhido.
        val ride = NavHold.plan(true, 2_000, true, true, uber, own, false)
        assertFalse(ride.passThrough)
        assertEquals(NavApp.WAZE, NavHold.chooseApp(geo, ride, NavApp.WAZE, both))
        assertEquals(NavApp.WAZE, NavHold.chooseApp(geo, NavHold.plan(true, 2_000, false, true, uber, own, false), NavApp.WAZE, both))
        assertTrue(NavHold.plan(true, 2_000, true, true, own, own, false).passThrough)
        assertFalse(NavHold.plan(true, 2_000, true, true, uber, own, true).passThrough)
    }

    @Test fun coordenada_impossivel_e_ignorada() {
        val r = NavHold.parse("waze://?ll=123,456&navigate=yes")!!
        assertNull(r.lat)
        assertNull(r.lon)
        assertTrue(r.navigate)
    }

    @Test fun link_do_botao_testar() {
        assertTrue(NavHold.parse(NavHold.TEST_URI)!!.test)
    }

    @Test fun segura_so_com_copiloto_leitor_e_app_de_corrida() {
        fun plan(
            enabled: Boolean = true, copilot: Boolean = true, reader: Boolean = true,
            referrer: String? = uber, test: Boolean = false, delay: Long = 3_000,
        ) = NavHold.plan(enabled, delay, copilot, reader, referrer, own, test)

        assertEquals(3_000L, plan().holdMs)
        assertEquals(3_000L, plan(referrer = null).holdMs) // quem chamou não se identificou: segura
        assertEquals(0L, plan(enabled = false).holdMs)
        assertEquals(0L, plan(copilot = false).holdMs)
        assertEquals(0L, plan(reader = false).holdMs)
        assertEquals(0L, plan(referrer = "com.whatsapp").holdMs)
        assertEquals(0L, plan(referrer = own).holdMs)
        assertEquals(0L, plan(test = true).holdMs)
        assertEquals(NavHold.DEFAULT_DELAY_MS, plan(delay = 777).holdMs)
        assertEquals("copiloto parado", plan(copilot = false).reason)
    }

    @Test fun motorista_no_waze_traz_a_uber_para_ler() {
        fun plan(
            onScreen: Boolean = false, bring: Boolean = true, referrer: String? = uber,
            last: Platform? = null, since: Long? = null,
        ) = NavHold.plan(true, 2_000, true, true, referrer, own, false, onScreen, bring, last, since)

        // A Uber mandou a rota com o Waze na frente: abre a Uber, espera o tempo + o extra.
        val p = plan()
        assertEquals(Platform.UBER, p.bring)
        assertEquals(2_000L + NavHold.BRING_EXTRA_MS, p.holdMs)
        // Uber na tela: só segura, como antes.
        assertNull(plan(onScreen = true).bring)
        assertEquals(2_000L, plan(onScreen = true).holdMs)
        // Opção desligada: segurar sem a Uber na tela não adianta, repassa na hora.
        assertEquals(0L, plan(bring = false).holdMs)
        assertEquals("Uber fora da tela", plan(bring = false).reason)
        // Quem pediu não se identificou: traz o último app de corrida visto pelo leitor.
        assertEquals(Platform.NINETY_NINE, plan(referrer = null, last = Platform.NINETY_NINE).bring)
        assertEquals(0L, plan(referrer = null, last = null).holdMs)
        // Acabou de trazer: não traz de novo (sem vaivém se o app reenviar a rota).
        assertEquals(0L, plan(since = 5_000).holdMs)
        assertEquals(Platform.UBER, plan(since = NavHold.BRING_COOLDOWN_MS).bring)
        // Com a Uber na tela, guarda quem trazer se ela sumir logo (janela de aceite por cima do Waze).
        assertEquals(Platform.UBER, plan(onScreen = true).fallback)
        assertNull(plan(onScreen = true, bring = false).fallback)
        assertNull(plan(onScreen = true, since = 5_000).fallback)
        assertTrue(NavHold.lateBring(reads = 0, rideAppOnScreen = false))
        assertFalse(NavHold.lateBring(reads = 1, rideAppOnScreen = false))
        assertFalse(NavHold.lateBring(reads = 0, rideAppOnScreen = true))
        // Os chamadores antigos (sem os parâmetros novos) continuam segurando como antes.
        assertEquals(2_000L, NavHold.plan(true, 2_000, true, true, uber, own, false).holdMs)
    }

    @Test fun registro_quando_traz_a_uber() {
        val r = NavHold.parse("waze://?ll=-23.55052,-46.63331&navigate=yes")!!
        assertEquals(
            "🧭 Waze pedido por Uber: -23.55052,-46.63331 · navegar · Uber fora da tela: trazendo o app para ler (até 3,5 s)",
            NavHold.describe(r, uber, HoldPlan(3_500, "", bring = Platform.UBER)),
        )
    }

    @Test fun libera_quando_le_a_tela_da_corrida() {
        assertEquals(ReleaseReason.STAGE, NavHold.release(300, 2_000, TripStage.ACCEPTED, 1))
        assertEquals(ReleaseReason.STAGE, NavHold.release(300, 2_000, TripStage.ON_TRIP, 1))
        // "Como foi a viagem?" não libera: a Uber ainda pode mostrar o próximo passageiro.
        assertNull(NavHold.release(300, 2_000, TripStage.FINISHED, 1))
        assertNull(NavHold.release(300, 2_000, null, 2))
        assertEquals(ReleaseReason.FRAMES, NavHold.release(1_400, 5_000, null, NavHold.FRAMES_ENOUGH))
        assertEquals(ReleaseReason.TIMEOUT, NavHold.release(2_000, 2_000, null, 0))
    }

    @Test fun situacao_do_padrao_no_android() {
        assertEquals(NavLinkState.OFF, NavHold.state(false, true, own, own))
        assertEquals(NavLinkState.NOT_INSTALLED, NavHold.state(true, false, null, own))
        assertEquals(NavLinkState.READY, NavHold.state(true, true, own, own))
        assertEquals(NavLinkState.APP_DEFAULT, NavHold.state(true, true, NavHold.WAZE_PACKAGE, own))
        assertEquals(NavLinkState.ASK, NavHold.state(true, true, "android", own))
        assertEquals(NavLinkState.ASK, NavHold.state(true, true, null, own))
        val maps = NavApp.GOOGLE_MAPS
        assertEquals(NavLinkState.APP_DEFAULT, NavHold.state(true, true, maps.packageName, own, maps))
        assertEquals(NavLinkState.ASK, NavHold.state(true, true, NavHold.WAZE_PACKAGE, own, maps))
        assertEquals(NavLinkState.READY, NavHold.state(true, true, own, own, maps))
        assertTrue(NavHold.linkMessage(NavLinkState.ASK, maps).contains("MApp → Sempre"))
        assertTrue(NavHold.linkMessage(NavLinkState.APP_DEFAULT, maps).contains("O Google Maps está como padrão"))
        val ask = mapOf(NavApp.WAZE to NavLinkState.ASK, maps to NavLinkState.ASK)
        assertTrue(NavHold.message(ask, 2_000).contains("MApp → Sempre"))
        val ready = mapOf(NavApp.WAZE to NavLinkState.NOT_INSTALLED, maps to NavLinkState.READY)
        assertTrue(NavHold.message(ready, 1_500, maps).contains("1,5 s"))
        assertTrue(NavHold.message(ready, 1_500, maps).contains("quando a Uber abrir o Google Maps,"))
        val off = mapOf(NavApp.WAZE to NavLinkState.OFF, maps to NavLinkState.OFF)
        assertTrue(NavHold.message(off, 2_000).contains("Ligue"))
        val none = mapOf(NavApp.WAZE to NavLinkState.NOT_INSTALLED, maps to NavLinkState.NOT_INSTALLED)
        assertTrue(NavHold.message(none, 2_000).contains("Nem o Waze nem o Google Maps"))
    }

    @Test fun textos_do_registro() {
        val r = NavHold.parse("waze://?ll=-23.55052,-46.63331&navigate=yes")!!
        assertEquals(
            "🧭 Waze pedido por Uber: -23.55052,-46.63331 · navegar · segurando até 2 s",
            NavHold.describe(r, uber, HoldPlan(2_000, "")),
        )
        assertEquals(
            "🧭 Waze pedido por com.whatsapp: -23.55052,-46.63331 · navegar · repassado na hora (pedido de outro app)",
            NavHold.describe(r, "com.whatsapp", HoldPlan(0, "pedido de outro app")),
        )
        assertEquals(
            "🧭 Waze aberto depois de 1,4 s · tela da corrida lida (2 leituras da tela)",
            NavHold.describeRelease(1_400, ReleaseReason.STAGE, 2),
        )
    }

    @Test fun rota_no_google_maps() {
        val nav = NavHold.parse("waze://?ll=-23.55052,-46.63331&navigate=yes")!!
        assertEquals("google.navigation:q=-23.550520,-46.633310&mode=d", NavHold.target(nav, NavApp.GOOGLE_MAPS))
        val point = NavHold.parse("waze://?ll=-23.55052,-46.63331")!!
        assertEquals("geo:-23.550520,-46.633310?q=-23.550520,-46.633310", NavHold.target(point, NavApp.GOOGLE_MAPS))
        val address = NavHold.parse("waze://?q=Pra%C3%A7a+da+S%C3%A9&navigate=yes")!!
        assertEquals("google.navigation:q=Pra%C3%A7a%20da%20S%C3%A9&mode=d", NavHold.target(address, NavApp.GOOGLE_MAPS))
        // Sem destino (botão Testar): só abre o app.
        assertNull(NavHold.target(NavHold.parse(NavHold.TEST_URI)!!, NavApp.GOOGLE_MAPS))
        // Waze: o link de sempre, igual.
        assertEquals("waze://?ll=-23.55052,-46.63331&navigate=yes", NavHold.target(nav, NavApp.WAZE))
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=-23.550520,-46.633310&travelmode=driving",
            NavHold.mapsWebUrl(nav),
        )
    }

    @Test fun escolha_do_app_de_navegacao() {
        assertEquals(NavApp.WAZE, NavApp.fromId(null))
        assertEquals(NavApp.WAZE, NavApp.fromId("xpto"))
        assertEquals(NavApp.GOOGLE_MAPS, NavApp.fromId(NavApp.GOOGLE_MAPS.id))
        val both = setOf(NavApp.WAZE, NavApp.GOOGLE_MAPS)
        // Google Maps escolhido mas desinstalado: a rota vai para o Waze, nunca fica sem navegação.
        assertEquals(NavApp.WAZE, NavHold.resolveApp(NavApp.GOOGLE_MAPS, setOf(NavApp.WAZE)))
        assertEquals(NavApp.GOOGLE_MAPS, NavHold.resolveApp(NavApp.GOOGLE_MAPS, both))
        assertEquals(NavApp.WAZE, NavHold.resolveApp(NavApp.WAZE, both))
        // Só o Google Maps instalado (Uber no Google Maps, Waze escolhido): vai para o Google Maps.
        assertEquals(NavApp.GOOGLE_MAPS, NavHold.resolveApp(NavApp.WAZE, setOf(NavApp.GOOGLE_MAPS), NavApp.GOOGLE_MAPS))
        assertEquals(NavApp.GOOGLE_MAPS, NavHold.resolveApp(NavApp.WAZE, setOf(NavApp.GOOGLE_MAPS)))
        val r = NavHold.parse("waze://?ll=-23.55052,-46.63331&navigate=yes")!!
        assertEquals(
            "🧭 Waze pedido por Uber: -23.55052,-46.63331 · navegar · segurando até 2 s · rota no Google Maps",
            NavHold.describe(r, uber, HoldPlan(2_000, ""), NavApp.GOOGLE_MAPS),
        )
        assertEquals(
            "🧭 Google Maps aberto depois de 1,4 s · tempo esgotado (0 leituras da tela)",
            NavHold.describeRelease(1_400, ReleaseReason.TIMEOUT, 0, NavApp.GOOGLE_MAPS),
        )
        val ready = mapOf(NavApp.WAZE to NavLinkState.READY, NavApp.GOOGLE_MAPS to NavLinkState.READY)
        assertTrue(NavHold.message(ready, 2_000, NavApp.GOOGLE_MAPS).contains("abrir o Waze ou o Google Maps"))
        assertTrue(NavHold.message(ready, 2_000, NavApp.GOOGLE_MAPS).contains("abre o Google Maps"))
    }

    @Test fun tempos_e_site_do_waze() {
        assertEquals("1 s", NavHold.label(1_000))
        assertEquals("1,5 s", NavHold.label(1_500))
        assertEquals(NavHold.DEFAULT_DELAY_MS, NavHold.validDelay(0))
        assertEquals(4_000L, NavHold.validDelay(4_000))
        val r = NavHold.parse("waze://?ll=1,2&navigate=yes")!!
        assertEquals("https://waze.com/ul?ll=1,2&navigate=yes", NavHold.webUrl(r))
        assertEquals("https://waze.com/ul", NavHold.webUrl(NavHold.parse("waze://")!!))
    }
}
