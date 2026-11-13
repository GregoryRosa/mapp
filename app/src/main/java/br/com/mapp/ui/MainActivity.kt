package br.com.mapp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.mapp.finance.FinanceScreen
import br.com.mapp.finance.HistoryScreen
import br.com.mapp.finance.ProfilesScreen
import br.com.mapp.finance.ReportsScreen
import br.com.mapp.finance.VehicleScreen
import br.com.mapp.maps.ZoneEditorScreen

/** Rotas do app. Cada tela recebe apenas onBack; dados via MappApp.repos. */
object Routes {
    const val HOME = "home"
    const val FINANCE = "finance"
    const val REPORTS = "reports"
    const val HISTORY = "history"
    const val PROFILES = "profiles"
    const val VEHICLE = "vehicle"
    const val ZONES = "zones"
    const val CARD_STYLE = "card_style"
    const val SHIFTS = "shifts"
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
                Box(Modifier.fillMaxSize().background(MappColors.backgroundBrush)) { MappNavHost() }
            }
        }
    }
}

@Composable
private fun MappNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigate(it) }) }
        composable(Routes.FINANCE) { FinanceScreen(onBack = back) }
        composable(Routes.REPORTS) { ReportsScreen(onBack = back) }
        composable(Routes.HISTORY) { HistoryScreen(onBack = back) }
        composable(Routes.PROFILES) { ProfilesScreen(onBack = back) }
        composable(Routes.VEHICLE) { VehicleScreen(onBack = back) }
        composable(Routes.ZONES) { ZoneEditorScreen(onBack = back) }
        composable(Routes.CARD_STYLE) { CardStyleScreen(onBack = back) }
        composable(Routes.SHIFTS) { ShiftHistoryScreen(onBack = back) }
    }
}
