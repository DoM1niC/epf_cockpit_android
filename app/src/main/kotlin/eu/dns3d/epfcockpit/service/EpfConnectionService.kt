package eu.dns3d.epfcockpit.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.dns3d.epfcockpit.EpfApplication
import eu.dns3d.epfcockpit.MainActivity
import eu.dns3d.epfcockpit.R
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * Hält die App im Foreground während wir mit dem Scooter verbunden sind.
 * Die Notification zeigt Live-km/h + Akku — bleibt sichtbar auch wenn die
 * Activity zerstört wird (Screen aus, Multitasking).
 *
 * Lifecycle:
 *   - [ForegroundController] startet/stoppt den Service per
 *     `Context.startForegroundService(...)` / `stopService(...)` basierend
 *     auf `BleRepository.connection`.
 *   - [onTaskRemoved] (User swiped App aus Recents): wir killen den Process
 *     komplett — kein lingering Service, kein BLE-Heartbeat im Hintergrund.
 *     Genau das Verhalten was der User für eine "Ride-only"-App will.
 */
@AndroidEntryPoint
class EpfConnectionService : Service() {

    @Inject lateinit var ble: BleRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(initialNotification())
        observerJob = scope.launch {
            combine(ble.telemetry, ble.connection) { t, c -> t to c }
                .collect { (t, c) ->
                    val title = when (c) {
                        is ConnectionState.Connected ->
                            if (c.authenticated) "Cockpit verbunden" else "Cockpit – nicht authentifiziert"
                        is ConnectionState.Connecting -> "Cockpit verbindet…"
                        is ConnectionState.Failed -> "Cockpit – ${c.reason}"
                        ConnectionState.Disconnected -> "Cockpit getrennt"
                    }
                    val body = if (t != null) {
                        "%.1f km/h  •  Akku %d%%  •  %d W".format(t.speedKmh, t.batteryPercent, t.watts.toInt())
                    } else {
                        "Warte auf Telemetrie…"
                    }
                    updateNotification(title, body)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY damit Android den Service nach OOM-Kill wieder hochzieht,
        // solange wir nicht explizit stopSelf() rufen.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed — full shutdown")
        // 1) BLE sauber trennen (Heartbeat-Job stoppt, requestDisconnect)
        runCatching { ble.disconnect() }
        // 2) Notification weg, Service stoppen
        stopSelf()
        // 3) Coroutines killen
        scope.cancel()
        // 4) Process exit — Activity wäre eh schon durch Recents-Swipe weg,
        //    aber so verschwindet auch der Service-Prozess endgültig.
        exitProcess(0)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // --- Notification helpers ------------------------------------------------

    private fun initialNotification() = buildNotification("Cockpit startet…", "Warte auf Telemetrie…")

    private fun buildNotification(title: String, body: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, EpfApplication.CHANNEL_COCKPIT)
            .setSmallIcon(R.drawable.ic_notif_scooter)
            .setContentTitle(title)
            .setContentText(body)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE /* 34 */) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(title: String, body: String) {
        val nm = NotificationManagerCompat.from(this)
        // POST_NOTIFICATIONS-Permission kann fehlen — NotificationManagerCompat
        // schluckt dann silently, kein extra Try-Catch nötig.
        if (nm.areNotificationsEnabled()) {
            nm.notify(NOTIF_ID, buildNotification(title, body))
        }
    }

    private companion object {
        const val TAG = "EpfConnectionService"
        const val NOTIF_ID = 4711
    }
}
