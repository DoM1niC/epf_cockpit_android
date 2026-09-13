package eu.dns3d.epfcockpit.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.DateFormat
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
import eu.dns3d.epfcockpit.ble.ReconnectStatus
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.data.LocationCollector
import eu.dns3d.epfcockpit.data.SettingsStore
import eu.dns3d.epfcockpit.widget.UnlockTileService
import eu.dns3d.epfcockpit.widget.UnlockWidgetProvider
import eu.dns3d.epfcockpit.widget.WidgetStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * Hält die App im Foreground während wir mit dem Scooter verbunden sind ODER
 * nach einem Abriss eine Reconnect-Episode läuft. Die Notification zeigt
 * Live-km/h + Akku bzw. den ehrlichen Reconnect-Zustand — bleibt sichtbar auch
 * wenn die Activity zerstört wird (Screen aus, Multitasking).
 *
 * Lifecycle:
 *   - [ForegroundController] startet/stoppt den Service per
 *     `Context.startForegroundService(...)` / `stopService(...)` basierend
 *     auf `BleRepository.keepServiceAlive`.
 *   - [onTaskRemoved] (User swiped App aus Recents): wir killen den Process
 *     komplett — kein lingering Service, kein BLE-Heartbeat im Hintergrund.
 *     Genau das Verhalten was der User für eine "Ride-only"-App will.
 */
@AndroidEntryPoint
class EpfConnectionService : Service() {

    @Inject lateinit var ble: BleRepository
    @Inject lateinit var settings: eu.dns3d.epfcockpit.data.SettingsStore

    // Eager-Inject damit der Singleton initialisiert wird sobald der FGS
    // läuft — sonst wäre der LocationCollector erst beim ersten Hilt-Lookup
    // wach (was z.B. erst beim Öffnen der Settings passieren würde).
    @Suppress("unused") @Inject lateinit var locationCollector: LocationCollector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    /** Zuletzt gepostete Notification — NMS verwirft >5 Updates/s, also nur bei Änderung posten. */
    private var lastNotificationKey: String? = null

    /** Zusammengefasster Observer-State (combine hat kein Quintuple). */
    private data class ObsState(
        val t: Telemetry?,
        val c: ConnectionState,
        val s: SettingsStore.Snapshot,
        val park: Boolean?,
        val r: ReconnectStatus,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(buildNotification("E-Scooter startet…", "Warte auf Telemetrie…", emptyList()))
        // Erst NACH startForeground als laufend melden: der ForegroundController
        // stoppt nur laufende Services — ein stopService vor startForeground
        // würde die App mit "did not call startForeground" crashen.
        _running.value = true
        observerJob = scope.launch {
            // parkingDisplay (verifizierter Park-Zustand aus dem ParkController)
            // statt des rohen Telemetrie-Bits — so spiegeln Widget/Tile denselben
            // bestätigten Zustand wie das Dashboard, inkl. Failed→Realität.
            combine(
                ble.telemetry, ble.connection, settings.flow, ble.parkingDisplay, ble.reconnectStatus,
            ) { t, c, s, park, r ->
                ObsState(t, c, s, park, r)
            }
                .collect { (t, c, s, park, r) ->
                    // Nur posten solange der Service laufen soll: sonst könnte ein
                    // notify nach stopService als verwaiste ongoing-Notification
                    // ohne FGS stehen bleiben.
                    if (BleRepository.shouldKeepServiceAlive(c, r)) {
                        renderNotification(t, c, r)
                    }
                    // Home-Widget + Quick-Tile mit aktuellem Park/Mode-Zustand fuettern.
                    val isConnected = c is ConnectionState.Connected
                    val isAuth = (c as? ConnectionState.Connected)?.authenticated == true
                    WidgetStateHolder.update(
                        connected = isConnected,
                        authenticated = isAuth,
                        parking = park,
                        rideModeStand = s.rideModeStand,
                    )
                    UnlockWidgetProvider.pushState(
                        context = this@EpfConnectionService,
                        connected = isConnected,
                        authenticated = isAuth,
                        parking = park,
                        rideModeStand = s.rideModeStand,
                    )
                    UnlockTileService.requestRefresh(this@EpfConnectionService)
                }
        }
    }

    private fun renderNotification(t: Telemetry?, c: ConnectionState, r: ReconnectStatus) {
        val telemetryBody = if (t != null) {
            "%.1f km/h  •  Akku %d%%  •  %d W".format(t.speedKmh, t.batteryPercent, t.watts.toInt())
        } else {
            "Warte auf Telemetrie…"
        }
        val (title, body) = when {
            c is ConnectionState.Connected ->
                (if (c.authenticated) "E-Scooter verbunden" else "E-Scooter – nicht authentifiziert") to telemetryBody
            r is ReconnectStatus.Searching && r.attempt > 0 ->
                "E-Scooter – Verbindung verloren" to "Verbinde neu… (Versuch ${r.attempt}/${r.max})"
            r is ReconnectStatus.Searching ->
                "E-Scooter – Verbindung verloren" to "Verbinde neu…"
            r is ReconnectStatus.Waiting ->
                "E-Scooter – Verbindung verloren" to
                    "Verbindet automatisch, sobald der Roller erreichbar ist (bis ${formatTime(r.untilWallMs)})"
            r is ReconnectStatus.Paused ->
                "E-Scooter – Auto-Verbinden pausiert" to "Andere App aktiv? Handy sperren setzt fort – oder „Verbinden“ tippen"
            r is ReconnectStatus.Exhausted ->
                "E-Scooter – Roller nicht erreichbar" to "Auto-Suche pausiert – „Verbinden“ tippen oder Handy sperren"
            r is ReconnectStatus.ForeignLink ->
                "E-Scooter – mit anderer App verbunden" to "Verbindet automatisch, sobald der Roller wieder frei ist"
            r is ReconnectStatus.BluetoothOff ->
                "E-Scooter – Bluetooth ist aus" to
                    "Verbindet wieder, sobald Bluetooth an ist (bis ${formatTime(r.untilWallMs)})"
            c is ConnectionState.Connecting -> "E-Scooter verbindet…" to telemetryBody
            c is ConnectionState.Failed -> "E-Scooter – ${c.reason}" to telemetryBody
            else -> "E-Scooter getrennt" to telemetryBody
        }
        val actions = buildList {
            if (c is ConnectionState.Connected || r !is ReconnectStatus.Idle) add(Action.DISCONNECT)
            if (r is ReconnectStatus.Paused || r is ReconnectStatus.Exhausted) add(Action.CONNECT)
        }
        updateNotification(title, body, actions)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Quick-Tile / Widget / Notification-Actions.
        when (intent?.action) {
            ACTION_UNLOCK -> handleAction { ble.setParking(false) }
            ACTION_TOGGLE_PARK -> scope.launch {
                // Vom verifizierten Anzeigewert toggeln (nicht vom rohen
                // Telemetrie-Bit), damit der Toggle konsistent zum Dashboard ist.
                val current = ble.parkingDisplay.value
                if (current == null && !isAuthenticated()) {
                    // Park-Zustand unbekannt (nicht verbunden): nicht blind parken,
                    // nur verbinden. Der User tippt nach dem Connect erneut.
                    ble.kickReconnectForAction()
                } else {
                    handleAction { ble.setParking(!(current ?: false)) }
                }
            }
            ACTION_TOGGLE_RIDE_MODE -> scope.launch {
                val next = !settings.flow.first().rideModeStand
                // Mode erst persistieren, wenn der Befehl wirklich rausgeht.
                handleAction {
                    ble.setRideMode(next)
                    scope.launch { settings.setRideModeStand(next) }
                }
            }
            ACTION_CONNECT -> {
                NotificationManagerCompat.from(this).cancel(ReconnectHintNotifier.HINT_ID)
                ble.kickReconnectForAction()
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "notification action: Trennen")
                ble.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // START_NOT_STICKY: nach Process-Death soll Android den Service NICHT
        // automatisch wieder hochziehen — sonst kommt die Notification nach
        // `exitProcess` aus onTaskRemoved sofort als "Cockpit getrennt" zurück.
        // Trade-off: bei rare OOM-Kill ist der Service auch weg. FGS sind
        // praktisch nie OOM-Opfer, der Trade-off ist es wert.
        return START_NOT_STICKY
    }

    private fun isAuthenticated(): Boolean =
        (ble.connection.value as? ConnectionState.Connected)?.authenticated == true

    /**
     * Sendet einen BLE-Befehl. Wenn die Verbindung gerade nicht authentifiziert
     * ist, stößt er einen Reconnect an und wartet bis zu 20 s auf den
     * Connected+Auth-Zustand — sodass ein Widget-Klick direkt nach App-Start
     * (Auto-Connect noch laufend) trotzdem funktioniert. Der Service bleibt
     * dabei an (die Reconnect-Episode hält ihn), der Befehl geht also nicht
     * mehr verloren, wenn Phase 1 scheitert.
     */
    private fun handleAction(block: () -> Unit) {
        scope.launch {
            if (isAuthenticated()) {
                block()
                return@launch
            }
            updateNotification("E-Scooter verbindet…", "Befehl wartet auf Verbindung", listOf(Action.DISCONNECT))
            // Bewusste Aktion (Widget/Tile) → Reconnect erzwingen, auch wenn die
            // App im Hintergrund ist (der passive Auto-Reconnect wartet dort evtl. nur).
            ble.kickReconnectForAction()
            val ready = withTimeoutOrNull(ACTION_WAIT_MS) {
                ble.connection.first { it is ConnectionState.Connected && it.authenticated }
            }
            if (ready != null) block()
            else updateNotification("E-Scooter getrennt", "Befehl abgebrochen — keine Verbindung", listOf(Action.DISCONNECT))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed — full shutdown")
        // 1) BLE sauber trennen (Heartbeat-Job stoppt, Episode endet, requestDisconnect)
        runCatching { ble.disconnect() }
        // 2) Notifications explizit entfernen, BEVOR der Process stirbt.
        //    Ohne STOP_FOREGROUND_REMOVE bleibt sie kurz als "Cockpit getrennt"
        //    sichtbar weil der NotificationManager noch nichts vom Service-
        //    Ende mitbekommen hat.
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(ReconnectHintNotifier.HINT_ID)
        stopSelf()
        // 3) Coroutines killen
        scope.cancel()
        // 4) Process exit — Activity wäre eh schon durch Recents-Swipe weg,
        //    aber so verschwindet auch der Service-Prozess endgültig.
        exitProcess(0)
    }

    override fun onDestroy() {
        _running.value = false
        observerJob?.cancel()
        scope.cancel()
        // Race: der Observer kann nach ForegroundController.stopService() noch
        // nm.notify(NOTIF_ID, ongoing) gepostet haben — ohne FGS-Flag bliebe sie
        // als verwaiste Notification stehen. Nach scope.cancel() postet niemand mehr.
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        super.onDestroy()
    }

    // --- Notification helpers ------------------------------------------------

    private enum class Action { DISCONNECT, CONNECT }

    private fun formatTime(wallMs: Long): String = DateFormat.getTimeFormat(this).format(Date(wallMs))

    private fun buildNotification(title: String, body: String, actions: List<Action>): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, EpfApplication.CHANNEL_COCKPIT)
            .setSmallIcon(R.drawable.ic_notif_scooter)
            .setContentTitle(title)
            .setContentText(body)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        actions.forEach { action ->
            when (action) {
                Action.DISCONNECT -> builder.addAction(0, "Trennen", servicePendingIntent(ACTION_DISCONNECT, REQ_DISCONNECT))
                Action.CONNECT -> builder.addAction(0, "Verbinden", servicePendingIntent(ACTION_CONNECT, REQ_CONNECT))
            }
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, EpfConnectionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun startInForeground(n: android.app.Notification) {
        // FGS-Type LOCATION braucht zusaetzlich zur Runtime-Permission auch dass
        // der Service AUS DEM FOREGROUND gestartet wird — bei einem Start aus dem
        // Hintergrund (Widget/Tile/Notification-Action bei geschlossener App)
        // wirft startForeground SecurityException. Wir probieren also erst mit
        // LOCATION, fallen bei Misserfolg auf CONNECTED_DEVICE-only zurueck (GPS
        // funktioniert dann zwar im Background nicht, aber das ist besser als
        // App-Crash). Ein Reconnect nach Verbindungsabbruch startet den FGS nicht
        // mehr neu (er bleibt über die Episode an), der LOCATION-Typ bleibt dort.
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
    }

    private fun updateNotification(title: String, body: String, actions: List<Action>) {
        val key = "$title|$body|$actions"
        if (key == lastNotificationKey) return
        val nm = NotificationManagerCompat.from(this)
        // POST_NOTIFICATIONS-Permission kann fehlen — NotificationManagerCompat
        // schluckt dann silently, kein extra Try-Catch nötig.
        if (nm.areNotificationsEnabled()) {
            nm.notify(NOTIF_ID, buildNotification(title, body, actions))
            lastNotificationKey = key
        }
    }

    companion object {
        private const val TAG = "EpfConnectionService"
        private const val NOTIF_ID = 4711
        private const val REQ_DISCONNECT = 11
        private const val REQ_CONNECT = 12
        private const val ACTION_WAIT_MS = 20_000L

        private val _running = MutableStateFlow(false)
        /** true zwischen startForeground (onCreate) und onDestroy — für den [ForegroundController]. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Quick-Tile-Action: Roller immer entsperren (Park-Bremse lösen). */
        const val ACTION_UNLOCK = "eu.dns3d.epfcockpit.action.UNLOCK"
        /** Widget-Action: Park-Zustand toggeln (entsperrt ↔ gesperrt). */
        const val ACTION_TOGGLE_PARK = "eu.dns3d.epfcockpit.action.TOGGLE_PARK"
        /** Widget-Action: Stand-Stop ↔ Kick-Start umschalten. */
        const val ACTION_TOGGLE_RIDE_MODE = "eu.dns3d.epfcockpit.action.TOGGLE_RIDE_MODE"
        /** Widget/Tile/Notification: nur verbinden (kein Befehl). */
        const val ACTION_CONNECT = "eu.dns3d.epfcockpit.action.CONNECT"
        /** Notification: trennen und den Roller für andere Apps freigeben. */
        const val ACTION_DISCONNECT = "eu.dns3d.epfcockpit.action.DISCONNECT"
    }
}
