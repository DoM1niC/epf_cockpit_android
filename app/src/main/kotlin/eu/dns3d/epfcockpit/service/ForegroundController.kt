package eu.dns3d.epfcockpit.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.ReconnectHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Steuert die Lifecycle von [EpfConnectionService] passend zu
 * [BleRepository.keepServiceAlive]:
 *   - verbunden, verbindend ODER eine Reconnect-Episode läuft → Service starten
 *   - sonst → Service (nach kurzer Gnadenfrist) stoppen
 *
 * Der FGS bleibt damit über einen Verbindungsabbruch hinweg an — sonst würde
 * Android den Prozess einfrieren (Cached-App-Freezer) und ein späterer Start
 * aus dem Hintergrund wäre verboten.
 *
 * Application-scoped (in [eu.dns3d.epfcockpit.EpfApplication] injiziert), damit
 * das auch bei Kaltstart über Widget/Tile/Notification-Action gilt.
 */
@Singleton
class ForegroundController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: BleRepository,
    private val hints: ReconnectHintNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            // Zusätzlich den echten Service-Zustand beobachten: ein von Widget/
            // Tile/Notification direkt gestarteter Service (keepServiceAlive nie
            // true, z.B. Bluetooth aus) würde sonst nie gestoppt.
            combine(ble.keepServiceAlive, EpfConnectionService.running) { want, running -> want to running }
                .collectLatest { (want, running) ->
                    when {
                        want && !running -> startService()
                        !want && running -> {
                            // Gnadenfrist: Zwischenzustände (verspätete Events, Widget-
                            // Aktion, die gerade erst eine Episode startet) absorbieren.
                            delay(STOP_GRACE_MS)
                            stopService()
                        }
                    }
                }
        }
        scope.launch {
            // Nur echte Übergänge: der Startwert null eines frischen Prozesses darf
            // einen Hinweis aus einem früheren (gekillten) Prozess nicht löschen.
            var previous: ReconnectHint? = null
            ble.reconnectHint.collect { h ->
                if (h != null) hints.show(h) else if (previous != null) hints.cancel()
                previous = h
            }
        }
        // Quittieren als Ereignis: eine stehende Verbindung erledigt jeden Hinweis —
        // auch einen aus einem früheren Prozess, den der StateFlow nicht kennt.
        scope.launch {
            ble.connection.collect { c -> if (c is ConnectionState.Connected) hints.cancel() }
        }
        // App-Vordergrund/-Hintergrund an die BleRepository melden. Die Reconnect-
        // Policy unterscheidet damit "App vorne" von "gesperrt" und "andere App
        // vorne" (Single-Link-Handover). addObserver muss auf dem Main-Thread
        // laufen — scope ist Main.immediate, also passt das.
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    hints.cancel()
                    ble.onAppForegroundChanged(true)
                }
                override fun onStop(owner: LifecycleOwner) = ble.onAppForegroundChanged(false)
            })
        }
    }

    private fun startService() {
        try {
            context.startForegroundService(Intent(context, EpfConnectionService::class.java))
            Log.i(TAG, "started EpfConnectionService")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Sollte nicht mehr vorkommen (FGS bleibt über Abrisse an), aber nie crashen.
            Log.w(TAG, "FGS-Start aus dem Hintergrund verweigert: ${e.message}")
            hints.show(ReconnectHint.NOT_FOUND)
        }
    }

    private fun stopService() {
        context.stopService(Intent(context, EpfConnectionService::class.java))
        Log.i(TAG, "stopped EpfConnectionService")
    }

    private companion object {
        const val TAG = "ForegroundController"
        const val STOP_GRACE_MS = 2_000L
    }
}
