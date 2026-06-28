package eu.dns3d.epfcockpit.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Steuert die Lifecycle von [EpfConnectionService] passend zum
 * BLE-Connection-State:
 *   - sobald wir `Connected` oder `Connecting` sind → Service starten
 *   - sobald wir `Disconnected` oder `Failed` sind → Service stoppen
 *
 * Damit muss die Activity nichts über den Service wissen — das Lifecycle-
 * Mapping passiert zentral. Wird in MainActivity eagerly injected.
 */
@Singleton
class ForegroundController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: BleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            ble.connection
                .map { it.shouldRunService() }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    if (shouldRun) startService() else stopService()
                }
        }
        // App-Vordergrund/-Hintergrund an die BleRepository melden. Damit pausiert
        // der Auto-Reconnect im Hintergrund und greift sich den Single-Link-Roller
        // nicht weg, während der User in einer anderen App (z.B. der Hersteller-
        // App) verbinden will. addObserver muss auf dem Main-Thread laufen —
        // scope ist Main.immediate, also passt das.
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = ble.onAppForegroundChanged(true)
                override fun onStop(owner: LifecycleOwner) = ble.onAppForegroundChanged(false)
            })
        }
    }

    private fun startService() {
        val intent = Intent(context, EpfConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.i(TAG, "started EpfConnectionService")
    }

    private fun stopService() {
        context.stopService(Intent(context, EpfConnectionService::class.java))
        Log.i(TAG, "stopped EpfConnectionService")
    }

    private fun ConnectionState.shouldRunService(): Boolean = when (this) {
        is ConnectionState.Connected -> true
        is ConnectionState.Connecting -> true
        is ConnectionState.Failed -> false
        ConnectionState.Disconnected -> false
    }

    private companion object {
        const val TAG = "ForegroundController"
    }
}
