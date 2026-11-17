package br.com.mapp.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import br.com.mapp.Gate
import br.com.mapp.billing.EntitlementStore
import br.com.mapp.billing.PlayBilling
import br.com.mapp.core.billing.AccessRules
import br.com.mapp.finance.FinanceScreen
import br.com.mapp.finance.HistoryScreen
import br.com.mapp.finance.ProfilesScreen
import br.com.mapp.finance.rememberNow
import br.com.mapp.maps.ZoneEditorScreen
import br.com.mapp.support.SupportRepository

/** Rotas do app. Cada tela recebe apenas onBack; dados via MappApp.repos. */
object Routes {
    const val HOME = "home"
    /** Financeiro: abas Ganhos e gastos, Relatórios e Custos fixos. */
    const val FINANCE = "finance"
    const val HISTORY = "history"
    const val PROFILES = "profiles"
    const val ZONES = "zones"
    const val CARD_STYLE = "card_style"
    const val SHIFTS = "shifts"
    /** Chat com o suporte e report de bug (botão no canto de todas as telas). */
    const val SUPPORT = "support"
    /** Painel de chamados — só a conta do dono passa do login. */
    const val ADMIN = "admin"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barras do sistema no mesmo navy do app.
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF0A1C24.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0xFF14102A.toInt()
        setContent {
            MappTheme {
                Box(Modifier.fillMaxSize().background(MappColors.backgroundBrush)) { MappGate() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pega assinatura comprada, cancelada ou reativada fora do app.
        PlayBilling.refresh()
    }
}

/**
 * Portaria do app, na ordem em que o Google Play exige:
 * 1. **aviso de Acessibilidade** com aceite (uma vez, antes de qualquer permissão);
 * 2. **assinatura** ativa — sem ela só abrem a tela de assinatura, o suporte e o painel;
 * 3. o app.
 */
@Composable
private fun MappGate() {
    val ctx = LocalContext.current
    var consent by remember { mutableStateOf(Consent.accepted(ctx)) }
    val entitlement by EntitlementStore.state.collectAsStateWithLifecycle()
    val owner by SupportRepository.owner.collectAsStateWithLifecycle()
    val now = rememberNow()
    // APK de teste do GitHub não passa pela loja: ali não há assinatura para conferir.
    val fromPlay = remember { Gate.fromPlay(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Sessão anônima do suporte: o botão de suporte precisa dela para contar as respostas novas.
    LaunchedEffect(Unit) { SupportRepository.ensureSession(ctx) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { PlayBilling.refresh() }
    }

    if (!consent) {
        ConsentScreen(
            onAccept = { consent = true },
            onDecline = { (ctx as? Activity)?.finish() },
        )
        return
    }

    val access = AccessRules.access(entitlement, now, owner = owner, fromPlay = fromPlay)
    if (!AccessRules.isOpen(access)) {
        // Bloqueado: ainda assim dá para falar com o suporte e para o dono entrar no painel.
        var screen by remember { mutableStateOf(Routes.HOME) }
        when (screen) {
            Routes.SUPPORT -> SupportScreen(
                onBack = { screen = Routes.HOME },
                onAdmin = { screen = Routes.ADMIN },
            )
            Routes.ADMIN -> AdminScreen(onBack = { screen = Routes.HOME })
            else -> PaywallScreen(
                access = access,
                entitlement = entitlement,
                onSupport = { screen = Routes.SUPPORT },
                onAdmin = { screen = Routes.ADMIN },
            )
        }
        return
    }

    MappShell()
}

@Composable
private fun MappShell() {
    val nav = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        MappNavHost(nav)
        // Botão de suporte no canto de todas as telas (menos nas próprias telas de suporte).
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        if (route != Routes.SUPPORT && route != Routes.ADMIN) {
            SupportFab { nav.navigate(Routes.SUPPORT) }
        }
    }
}

@Composable
private fun MappNavHost(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigate(it) }) }
        composable(Routes.FINANCE) { FinanceScreen(onBack = back) }
        composable(Routes.HISTORY) { HistoryScreen(onBack = back) }
        composable(Routes.PROFILES) { ProfilesScreen(onBack = back) }
        composable(Routes.ZONES) { ZoneEditorScreen(onBack = back) }
        composable(Routes.CARD_STYLE) { CardStyleScreen(onBack = back) }
        composable(Routes.SHIFTS) { ShiftHistoryScreen(onBack = back) }
        composable(Routes.SUPPORT) {
            SupportScreen(onBack = back, onAdmin = { nav.navigate(Routes.ADMIN) })
        }
        composable(Routes.ADMIN) { AdminScreen(onBack = back) }
    }
}
