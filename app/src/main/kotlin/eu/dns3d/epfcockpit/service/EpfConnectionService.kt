package eu.dns3d.epfcockpit.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.dns3d.epfcockpit.EpfApplication
import eu.dns3d.epfcockpit.MainActivity
import eu.dns3d.epfcockpit.R
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.data.LocationCollector
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

    // Eager-Inject damit der Singleton initialisiert wird sobald der FGS
    // läuft — sonst wäre der LocationCollector erst beim ersten Hilt-Lookup
    // wach (was z.B. erst beim Öffnen der Settings passieren würde).
    @Suppress("unused") @Inject lateinit var locationCollector: LocationCollector

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
                            if (c.authenticated) "E-Scooter verbunden" else "E-Scooter – nicht authentifiziert"
                        is ConnectionState.Connecting -> "E-Scooter verbindet…"
                        is ConnectionState.Failed -> "E-Scooter – ${c.reason}"
                        ConnectionState.Disconnected -> "E-Scooter getrennt"
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
        // START_NOT_STICKY: nach Process-Death soll Android den Service NICHT
        // automatisch wieder hochziehen — sonst kommt die Notification nach
        // `exitProcess` aus onTaskRemoved sofort als "Cockpit getrennt" zurück.
        // Trade-off: bei rare OOM-Kill ist der Service auch weg. FGS sind
        // praktisch nie OOM-Opfer, der Trade-off ist es wert.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed — full shutdown")
        // 1) BLE sauber trennen (Heartbeat-Job stoppt, requestDisconnect)
        runCatching { ble.disconnect() }
        // 2) Notification explizit entfernen, BEVOR der Process stirbt.
        //    Ohne STOP_FOREGROUND_REMOVE bleibt sie kurz als "Cockpit getrennt"
        //    sichtbar weil der NotificationManager noch nichts vom Service-
        //    Ende mitbekommen hat.
        stopForeground(STOP_FOREGROUND_REMOVE)
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

    private fun initialNotification() = buildNotification("E-Scooter startet…", "Warte auf Telemetrie…")

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
            // FGS-Type LOCATION braucht zusaetzlich zur Runtime-Permission auch
            // dass der Service AUS DEM FOREGROUND gestartet wird — bei einem
            // BLE-Auto-Reconnect aus dem Hintergrund crasht startForeground mit
            // SecurityException. Wir probieren also erst mit LOCATION, fallen
            // bei Misserfolg auf CONNECTED_DEVICE-only zurueck (GPS funktioniert
            // dann zwar im Background nicht, aber das ist besser als App-Crash).
            val locGranted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val deviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val fullType = if (locGranted) {
                deviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else deviceType
            try {
                startForeground(NOTIF_ID, n, fullType)
            } catch (sec: SecurityException) {
                Log.w(TAG, "FGS with LOCATION rejected (background-start?), falling back to device-only: ${sec.message}")
                startForeground(NOTIF_ID, n, deviceType)
            }
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
