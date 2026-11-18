package br.com.mapp.core

import br.com.mapp.core.model.TripStage
import br.com.mapp.core.nav.HoldPlan
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

    @Test fun nao_e_link_do_waze() {
        assertNull(NavHold.parse(null))
        assertNull(NavHold.parse("google.navigation:q=-23.5,-46.6"))
        assertNull(NavHold.parse("https://maps.google.com/?q=1,2"))
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
        assertEquals(NavLinkState.NO_WAZE, NavHold.state(true, false, null, own))
        assertEquals(NavLinkState.READY, NavHold.state(true, true, own, own))
        assertEquals(NavLinkState.WAZE_DEFAULT, NavHold.state(true, true, NavHold.WAZE_PACKAGE, own))
        assertEquals(NavLinkState.ASK, NavHold.state(true, true, "android", own))
        assertEquals(NavLinkState.ASK, NavHold.state(true, true, null, own))
        assertTrue(NavHold.message(NavLinkState.ASK, 2_000).contains("MApp → Sempre"))
        assertTrue(NavHold.message(NavLinkState.READY, 1_500).contains("1,5 s"))
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
