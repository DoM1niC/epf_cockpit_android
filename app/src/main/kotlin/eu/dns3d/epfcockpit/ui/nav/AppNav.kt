package eu.dns3d.epfcockpit.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.dns3d.epfcockpit.ui.auth.PinScreen
import eu.dns3d.epfcockpit.ui.dashboard.DashboardScreen
import eu.dns3d.epfcockpit.ui.history.HistoryScreen
import eu.dns3d.epfcockpit.ui.scan.ScanScreen
import eu.dns3d.epfcockpit.ui.settings.SettingsScreen

object Routes {
    const val SCAN = "scan"
    const val PIN = "pin"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SCAN) {
        composable(Routes.SCAN) {
            ScanScreen(
                onConnected = {
                    nav.navigate(Routes.PIN) {
                        popUpTo(Routes.SCAN) { inclusive = false }
                    }
                },
            )
        }
        composable(Routes.PIN) {
            PinScreen(
                onAuthenticated = {
                    nav.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.SCAN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onDisconnect = {
                    nav.navigate(Routes.SCAN) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onForget = {
                    nav.navigate(Routes.SCAN) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { nav.popBackStack() })
        }
    }
}
