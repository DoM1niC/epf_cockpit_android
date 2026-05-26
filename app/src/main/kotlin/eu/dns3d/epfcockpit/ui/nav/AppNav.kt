package eu.dns3d.epfcockpit.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eu.dns3d.epfcockpit.ui.auth.PinScreen
import eu.dns3d.epfcockpit.ui.dashboard.DashboardScreen
import eu.dns3d.epfcockpit.ui.history.HistoryScreen
import eu.dns3d.epfcockpit.ui.nfc.NfcCloneScreen
import eu.dns3d.epfcockpit.ui.nfc.NfcReadScreen
import eu.dns3d.epfcockpit.ui.scan.ScanScreen
import eu.dns3d.epfcockpit.ui.settings.SettingsScreen

object Routes {
    const val SCAN = "scan"
    const val PIN = "pin"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val NFC = "nfc"
    /** Klon-Screen ohne Vorbefüllung. */
    const val NFC_CLONE = "nfc_clone"
    /** Klon-Screen mit Vorbefüllung der UID, z.B. aus dem zuletzt gelesenen Tag. */
    const val NFC_CLONE_WITH_UID = "nfc_clone?uid={uid}"
    fun nfcCloneWithUid(uidHex: String): String = "nfc_clone?uid=$uidHex"
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
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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
                onOpenNfc = { nav.navigate(Routes.NFC) },
                onOpenNfcClone = { nav.navigate(Routes.NFC_CLONE) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NFC) {
            NfcReadScreen(
                onBack = { nav.popBackStack() },
                onCloneUid = { uidHex ->
                    nav.navigate(Routes.nfcCloneWithUid(uidHex))
                },
            )
        }
        composable(Routes.NFC_CLONE) {
            NfcCloneScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.NFC_CLONE_WITH_UID,
            arguments = listOf(navArgument("uid") {
                type = NavType.StringType
                defaultValue = ""
            }),
        ) { backStack ->
            NfcCloneScreen(
                onBack = { nav.popBackStack() },
                preloadUidHex = backStack.arguments?.getString("uid"),
            )
        }
    }
}
