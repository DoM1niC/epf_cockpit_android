package eu.dns3d.epfcockpit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import eu.dns3d.epfcockpit.data.SettingsStore
import eu.dns3d.epfcockpit.data.ThemeMode
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import eu.dns3d.epfcockpit.ui.nav.AppNav
import eu.dns3d.epfcockpit.ui.theme.EpfCockpitTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    // Just inject the streamer so Hilt instantiates the @Singleton at app start
    // — its init block latches onto BleRepository and runs for the process lifetime.
    @Suppress("unused")
    @Inject lateinit var streamer: TelemetryStreamer

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsStore.flow.collectAsState(initial = null)
            EpfCockpitTheme(themeMode = themeMode?.themeMode ?: ThemeMode.SYSTEM) {
                AppNav()
            }
        }
    }
}
