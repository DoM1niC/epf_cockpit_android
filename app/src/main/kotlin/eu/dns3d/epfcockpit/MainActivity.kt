package eu.dns3d.epfcockpit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import eu.dns3d.epfcockpit.data.SettingsStore
import eu.dns3d.epfcockpit.data.ThemeMode
import eu.dns3d.epfcockpit.data.TripCoordinator
import eu.dns3d.epfcockpit.data.TripSyncer
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import eu.dns3d.epfcockpit.ui.nav.AppNav
import eu.dns3d.epfcockpit.ui.onboarding.StartupPermissionsDialog
import eu.dns3d.epfcockpit.ui.theme.EpfCockpitTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    // Just inject these @Singletons so Hilt instantiates them at app start
    // — ihre init-Blöcke laufen dann und hängen sich an die BleRepository-
    // Flows. Sonst würden Streaming / Trip-Recording erst beim ersten
    // expliziten Aufruf scharf geschaltet. (Der ForegroundController lebt
    // Application-scoped in EpfApplication.)
    @Suppress("unused") @Inject lateinit var streamer: TelemetryStreamer
    @Suppress("unused") @Inject lateinit var tripCoordinator: TripCoordinator
    @Suppress("unused") @Inject lateinit var tripSyncer: TripSyncer

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val snapshot by settingsStore.flow.collectAsState(initial = null)
            EpfCockpitTheme(themeMode = snapshot?.themeMode ?: ThemeMode.SYSTEM) {
                var dialogDismissed by remember { mutableStateOf(false) }
                val showStartup = snapshot != null && !dialogDismissed
                AppNav()
                if (showStartup) {
                    StartupPermissionsDialog(
                        settings = settingsStore,
                        seenBefore = snapshot?.startupPromptDone == true,
                        onDismiss = { dialogDismissed = true },
                    )
                }
            }
        }
    }
}
