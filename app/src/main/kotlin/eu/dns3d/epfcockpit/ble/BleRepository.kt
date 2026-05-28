package eu.dns3d.epfcockpit.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import javax.inject.Inject
import javax.inject.Singleton

/** Stable summary of a scooter we have seen during scanning. */
data class DiscoveredScooter(
    val address: String,
    val name: String?,
    val rssi: Int,
    val likelyScooter: Boolean = false,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val address: String, val authenticated: Boolean) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

@Singleton
class BleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val manager = ScooterManager(context)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * Sticky flag for the current process: true once the user pressed the
     * Trennen-button in the Dashboard. Suppresses auto-reconnect until the
     * app is killed and started again, so we don't fight the user when they
     * just want to scan for another scooter / take a break.
     */
    @Volatile var userDisconnected: Boolean = false
        private set

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    val telemetry: StateFlow<Telemetry?> = manager.telemetry
    val atReplies: SharedFlow<AtResponse> = manager.atReplies
    val rawFrames: SharedFlow<ByteArray> = manager.rawFrames

    /**
     * Latest voice-prompt state reported by the scooter (via `OK+TLVOICEOFF:N`).
     * `null` while we haven't asked the controller yet. The dashboard reads
     * this so the toggle reflects the real state on mount, not an optimistic
     * default that would be wrong half the time.
     */
    private val _voiceOff = MutableStateFlow<Boolean?>(null)
    val voiceOff: StateFlow<Boolean?> = _voiceOff.asStateFlow()

    /**
     * Intent-basierter Park-Zustand. Das Telemetrie-Bit 5 ist mehrdeutig
     * (geparkt vs Stand-Mode teilen sich das Bit), daher tracken wir Park als
     * Intent + blind retry gegen verlorene Frames (siehe [ParkController]).
     * [parkingDisplay] ist die einzige Quelle die jede Oberfläche anzeigt.
     */
    private val parkController = ParkController(scope, manager.telemetry, manager::writeLowFrame)
    val parkingDisplay: StateFlow<Boolean?> = parkController.parkingDisplay

    /**
     * Live-Cache der User-Max-Speed aus den Settings. Wird in JEDES `0xAF`-
     * Statusframe geschrieben (parking/mode/setMaxSpeed), damit ein Park-Toggle
     * den g3-Wert nicht zurück auf 20 km/h zwingt.
     */
    @Volatile private var cachedMaxSpeedKmh: Int = 20

    /**
     * Live-Cache des Ride-Mode (Stand vs Kick). Park-OFF muss den aktuell
     * gewählten Mode bewahren — sonst wechselt ein Park-OFF ungewollt auf Kick,
     * weil der Park-OFF-Frame (0x82) byte-identisch mit dem Kick-Frame ist.
     */
    @Volatile private var cachedRideModeStand: Boolean = true

    init {
        // Settings → Cache spiegeln, damit Frame-Builder synchron lesen können
        // ohne Coroutine-Hop. Erste Emission liefert auch sofort den Persistenz-
        // Default (20 km/h), bevor irgendein Frame ausgeht.
        scope.launch {
            settings.flow.collect { snap ->
                cachedMaxSpeedKmh = snap.gear3MaxKmh.coerceIn(1, 40)
                cachedRideModeStand = snap.rideModeStand
            }
        }
        // Connection-Lifecycle vom Nordic-Manager beobachten — sonst bleibt
        // _connection auf "Connected" hängen wenn der Scooter ausgeht oder
        // außer Reichweite fährt, weil der suspend connect() längst returnt
        // hat und niemand mehr zuhört.
        scope.launch {
            manager.connEvents.collect { event ->
                when (event) {
                    is ScooterManager.ConnEvent.Disconnected -> {
                        stopHeartbeat()
                        // Nie ein Pending ueber den Link-Verlust schleppen —
                        // sonst sieht der User nach dem Disconnect noch ~2.7s
                        // ein Phantom-Pending bevor die Timeouts durch sind.
                        parkController.onDisconnected()
                        if (event.userInitiated) {
                            _connection.value = ConnectionState.Disconnected
                        } else {
                            Log.i(TAG, "peer disconnected (reason=${event.reason}) — kicking reconnect")
                            _connection.value = ConnectionState.Failed(
                                friendlyDisconnectReason(event.reason)
                            )
                            maybeStartAutoReconnect()
                        }
                    }
                    is ScooterManager.ConnEvent.FailedToConnect -> {
                        _connection.value = ConnectionState.Failed(
                            friendlyDisconnectReason(event.reason)
                        )
                    }
                }
            }
        }
        // Roller schickt regelmäßig Echo-Frames `AF 01 19 00 <g1> <g2> <g3> 00…`
        // mit seinen aktuell gespeicherten Gear-Limits. Die nutzen wir als
        // Source-of-Truth für unseren Cache — verhindert dass ein veralteter
        // DataStore-Wert (z.B. nach versehentlichem Apply auf 5 km/h, oder
        // wenn der User in der EPF-App den Wert geändert hat während wir
        // disconnected waren) bei jedem Park/Mode-Toggle reingeschickt wird.
        scope.launch {
            manager.rawFrames.collect { frame ->
                // Echo-Frame-Identifikation:
                //   byte[0] = 0xAF, byte[1] = 0x01, byte[3] = 0x00
                if (frame.size >= 7
                    && frame[0] == BleConstants.FRAME_HEADER_EPF
                    && (frame[1].toInt() and 0xFF) == 0x01
                    && (frame[3].toInt() and 0xFF) == 0x00
                ) {
                    val scooterG3 = frame[6].toInt() and 0xFF
                    if (scooterG3 in 1..40 && scooterG3 != cachedMaxSpeedKmh) {
                        Log.i(TAG, "echo-sync: scooter g3=$scooterG3 (was cached=$cachedMaxSpeedKmh)")
                        cachedMaxSpeedKmh = scooterG3
                        scope.launch { settings.setGearLimits(g1 = 6, g2 = 15, g3 = scooterG3) }
                    }
                }
            }
        }
        scope.launch {
            manager.atReplies.collect { reply ->
                when (reply) {
                    AtResponse.PinOk -> {
                        _connection.update { current ->
                            if (current is ConnectionState.Connected) current.copy(authenticated = true)
                            else current
                        }
                        // Exact post-login flow captured from the official
                        // app: identify, query voice state, then 5x
                        // wake-up burst, then start the 600 ms heartbeat.
                        manager.sendAt(AtCommands.queryDevice())
                        manager.sendAt(AtCommands.queryVoiceOff())
                        scope.launch {
                            repeat(5) {
                                manager.writeLowFrame(ControlFrames.PULSE_INIT, label = "init")
                                delay(50)
                            }
                            startHeartbeat()
                        }
                    }
                    AtResponse.PinRejected -> _connection.update { current ->
                        if (current is ConnectionState.Connected) current.copy(authenticated = false)
                        else current
                    }
                    is AtResponse.VoiceOffState -> {
                        android.util.Log.i("BleRepository", "voiceOff state from scooter: ${reply.on}")
                        _voiceOff.value = reply.on
                    }
                    else -> Unit
                }
            }
        }
    }

    fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    /**
     * Cold flow of scan results — collect to start, cancel to stop. Set
     * [scootersOnly] = false to surface every advertising BLE device with a
     * name (helps debugging when the EPF Pulse Plus advertises under an
     * unexpected name).
     */
    fun scan(scootersOnly: Boolean = true): kotlinx.coroutines.flow.Flow<DiscoveredScooter> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareBatchingIfSupported(false)
            .setLegacy(true)
            .build()

        // An empty filter list triggers Android's "location deny list" guard
        // (`Skipping ScanClient ... for location deny list`) — so we always
        // pass at least one filter. A `Builder().build()` filter is a wildcard
        // (matches every advertisement) and is enough to satisfy the guard.
        //
        // We do NOT try to filter by device name at the platform level: the
        // ePF firmware emits its name in the Scan Response, and several
        // Android BLE stacks don't match `ScanFilter.setDeviceName` against
        // scan-response names — the scooter then silently never shows up.
        // Filtering happens in Kotlin instead, on the name we read out of the
        // ScanRecord (which DOES include the scan-response name).
        val filters: List<ScanFilter> = listOf(ScanFilter.Builder().build())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitResult(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach(::emitResult)
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
            private fun emitResult(result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                val likely = name != null && BleConstants.ADVERTISED_NAME_PREFIXES
                    .any { name.startsWith(it, ignoreCase = true) }
                if (scootersOnly && !likely) return
                if (!scootersOnly && name == null) return
                trySend(DiscoveredScooter(result.device.address, name, result.rssi, likely))
            }
        }
        scanner.startScan(filters, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    suspend fun connect(address: String) {
        val a = adapter() ?: run {
            _connection.value = ConnectionState.Failed("Bluetooth nicht verfügbar")
            return
        }
        userDisconnected = false  // explicit connect overrides the sticky flag
        val device: BluetoothDevice = a.getRemoteDevice(address)
        _connection.value = ConnectionState.Connecting(address)
        try {
            manager.connectTo(device)
            _connection.value = ConnectionState.Connected(address, false)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation (caller stopped us, e.g. user
            // pressed Stop or navigated away). NOT a connection failure —
            // just drop back to Disconnected and let the cancellation
            // bubble up so coroutine bookkeeping stays correct.
            _connection.value = ConnectionState.Disconnected
            throw ce
        } catch (t: Throwable) {
            _connection.value = ConnectionState.Failed(friendlyError(t))
        }
    }

    private fun friendlyError(t: Throwable): String {
        val raw = (t.message ?: t::class.java.simpleName).orEmpty()
        // Nordic surfaces GATT status codes either as numeric ("status 8")
        // or as enum names ("GATT_CONN_TIMEOUT"). We catch both.
        return when {
            raw.contains("CONN_TIMEOUT", true) || raw.contains("status 8") ->
                "Scooter nicht erreichbar – ist er eingeschaltet?"
            raw.contains("TERMINATE_PEER_USER", true) || raw.contains("status 19") ->
                "Scooter hat die Verbindung beendet"
            raw.contains("CONN_TERMINATE", true) || raw.contains("status 22") ->
                "Verbindung unterbrochen"
            raw.contains("CONN_LMP_TIMEOUT", true) || raw.contains("status 34") ->
                "Signal zu schwach – näher an den Scooter"
            raw.contains("CONN_FAIL_ESTABLISH", true) || raw.contains("status 62") ->
                "Verbindung konnte nicht aufgebaut werden"
            raw.contains("ConnectionRequest", true) && raw.contains("cancelled", true) ->
                "Verbindungsversuch abgebrochen"
            raw.contains("DeviceDisconnectedException", true) ->
                "Scooter hat die Verbindung beendet"
            raw.contains("RequestFailedException", true) -> {
                val status = Regex("status: (-?\\d+)").find(raw)?.groupValues?.get(1)
                if (status != null) "Verbindung fehlgeschlagen (Code $status)"
                else "Verbindung fehlgeschlagen"
            }
            raw.contains("timeout", true) -> "Zeitüberschreitung"
            raw.isBlank() -> "Unbekannter Verbindungsfehler"
            else -> raw
        }
    }

    fun authenticate(pin: String) = manager.authenticate(pin)
    fun changePin(newPin: String) = manager.changePin(newPin)
    fun sendControl(state: ControlState) = manager.sendControl(state)

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        manager.requestDisconnect()
        _connection.value = ConnectionState.Disconnected
    }

    /**
     * Zwei-Phasen-Auto-Reconnect identisch zum [eu.dns3d.epfcockpit.ui.scan.ScanViewModel]:
     *   Phase 1 (≤6 s): direkter Connect auf gespeicherte MAC
     *   Phase 2 (≤15 s): Scan-by-Name, dann Connect auf frische MAC
     *
     * Wird vom Disconnect-Observer aufgerufen wenn die Verbindung *nicht*
     * vom User abgerissen wurde und [SettingsStore.autoConnect] an ist.
     * Im Gegensatz zur ScanVM lebt das hier im Repository, also auch wenn
     * der User gerade auf Dashboard / Settings / History ist statt auf Scan.
     */
    private fun maybeStartAutoReconnect() {
        if (userDisconnected) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val snap = settings.flow.first()
            if (!snap.autoConnect) {
                Log.i(TAG, "auto-reconnect skipped (autoConnect=off)")
                return@launch
            }
            val savedAddr = snap.lastScooterAddress ?: run {
                Log.i(TAG, "auto-reconnect skipped (no remembered address)")
                return@launch
            }
            val savedName = snap.lastScooterName
            val savedPin = snap.savedPin

            // Loop forever (mit Exponential-Backoff) bis entweder:
            //   - wir erfolgreich connected sind, oder
            //   - der User manuell trennt (userDisconnected = true), oder
            //   - der Scope gecancelt wird (App-Shutdown)
            // Sonst muss der User manuell auf "Suchen" tippen sobald der
            // Scooter wieder an ist — genau die Beschwerde aus dem Bug-Report.
            var attempt = 0
            while (isActive && !userDisconnected) {
                attempt++
                // Falls jemand parallel manuell connected hat — beenden.
                if (_connection.value is ConnectionState.Connected) {
                    reauthenticateIfPossible(savedPin)
                    return@launch
                }

                Log.i(TAG, "auto-reconnect attempt #$attempt — phase 1 (direct to $savedAddr)")
                try {
                    withTimeoutOrNull(6_000L) { connect(savedAddr) }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "phase 1 failed: ${t.message}")
                }
                if (_connection.value is ConnectionState.Connected) {
                    reauthenticateIfPossible(savedPin)
                    return@launch
                }

                Log.i(TAG, "auto-reconnect attempt #$attempt — phase 2 (scan by name '$savedName')")
                val match = try {
                    withTimeoutOrNull(10_000L) {
                        scan(scootersOnly = true).firstOrNull { found ->
                            (!savedName.isNullOrBlank() &&
                                found.name?.equals(savedName, ignoreCase = true) == true) ||
                                found.address.equals(savedAddr, ignoreCase = true)
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "phase 2 scan failed: ${t.message}")
                    null
                }
                if (match != null) {
                    settings.rememberScooter(match.address, match.name)
                    runCatching { connect(match.address) }
                    if (_connection.value is ConnectionState.Connected) {
                        reauthenticateIfPossible(savedPin)
                        return@launch
                    }
                }

                // Beide Phasen durch — Scooter ist offline. Backoff zwischen
                // den Versuchen damit wir nicht ständig das BLE-Radio scannen
                // (Akku) und nicht im 1-Sekunden-Takt failende GATTs feuern.
                val delayMs = when (attempt) {
                    1 -> 10_000L
                    2 -> 20_000L
                    3 -> 30_000L
                    else -> 60_000L
                }
                Log.i(TAG, "auto-reconnect attempt #$attempt failed, waiting ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    /**
     * Nach Auto-Reconnect-Connect die gespeicherte PIN erneut senden — sonst
     * bleibt der Status auf "Connected (authenticated=false)" und die App
     * sieht zwar das BLE-Link, aber der Scooter beantwortet keine
     * Telemetrie-Notifications. Wenn keine PIN gespeichert ist (User hat
     * den "PIN nicht merken"-Pfad gewählt), bleibt das manueller Pfad.
     */
    private fun reauthenticateIfPossible(savedPin: String?) {
        if (savedPin.isNullOrBlank()) {
            Log.i(TAG, "post-reconnect: no saved PIN, skipping re-auth")
            return
        }
        Log.i(TAG, "post-reconnect: re-auth with saved PIN")
        // Kurze Pause, damit der Manager die Init-Phase fertig macht
        // (initialize() = MTU + Notification-Subscriptions). Vorher wird
        // sendAt() einen null highTx haben.
        scope.launch {
            kotlinx.coroutines.delay(500)
            authenticate(savedPin)
        }
    }

    private fun friendlyDisconnectReason(reason: Int): String = when (reason) {
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_TERMINATE_PEER_USER ->
            "Scooter hat die Verbindung beendet"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_LINK_LOSS ->
            "Verbindung verloren – Signal weg"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_TIMEOUT ->
            "Zeitüberschreitung"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_NOT_SUPPORTED ->
            "Gerät nicht unterstützt"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_CANCELLED ->
            "Verbindungsversuch abgebrochen"
        else -> "Verbindung unterbrochen (reason=$reason)"
    }

    private companion object {
        const val TAG = "BleRepository"
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                manager.writeLowFrame(ControlFrames.PULSE_HEARTBEAT, label = "hb")
                delay(600)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // === High-level Pulse Plus controls (sniff-verified) ===
    // Frame g3 = cachedMaxSpeedKmh (vom Echo-Sync gepflegt) — der Controller
    // bekommt bei jedem Park/Mode-Toggle den aktuellen User-Max-Speed mit,
    // damit der nicht durch das nächste Toggle wieder auf einen alten Wert
    // resettet wird.

    fun setParking(on: Boolean) {
        val g3 = cachedMaxSpeedKmh
        val frame = if (on) {
            ControlFrames.parkingOn(g3)            // 0x22 — parken
        } else {
            // Park-OFF im aktuell gewählten Mode senden, damit ein Park-OFF nicht
            // ungewollt auf Kick wechselt: der generische parkingOff-Frame (0x82)
            // IST der Kick-Frame. Stand → 0xA2 (entparkt + Stand), Kick → 0x82.
            if (cachedRideModeStand) ControlFrames.modeStandStop(g3) else ControlFrames.modeKickStart(g3)
        }
        parkController.setParking(target = on, frame = frame, label = if (on) "park-on" else "park-off")
    }

    /**
     * Ride mode toggle. `stand=true` → Stand-Stop (must be pushed before
     * motor engages, scooter also parks). `stand=false` → Kick-Start
     * (goes on throttle alone, scooter also unparks).
     * NB: this overlaps with [setParking] because the official app uses the
     * same frames for both — switching modes also flips the park state.
     */
    fun setRideMode(stand: Boolean) {
        val g3 = cachedMaxSpeedKmh
        val frame = if (stand) ControlFrames.modeStandStop(g3) else ControlFrames.modeKickStart(g3)
        // Direkter Write, NICHT durch die Verify-Logik (sonst Spinner/Fehler am
        // falschen Control). Ein Mode-Wechsel deaktiviert physisch das Parken,
        // also den Park-Latch sofort auf "entparkt" setzen, damit der Switch
        // mitzieht und der State stimmt.
        manager.writeLowFrame(frame, label = if (stand) "mode-stand" else "mode-kick")
        parkController.notePark(false)
    }

    /**
     * Setzt die Max-Geschwindigkeit (byte [7] = g3) durch ein Stand-Stop-Frame
     * mit dem neuen Wert — mirror dem Verhalten der offiziellen EPF-App, die
     * beim Slider-Move ebenfalls Park+Stand sendet (Sniff #3 bestätigt). Der
     * neue Wert wird persistiert und in den Cache geschrieben, sodass das
     * nächste Park-/Mode-Frame ihn weiter trägt.
     *
     * Caller-Pflicht: nur aufrufen wenn der Roller geparkt (oder steht) ist —
     * sonst wechselt der Roller mid-ride in Stand-Stop, das ist kein guter
     * Tag. Die Settings-UI disabled den Button entsprechend.
     */
    fun setMaxSpeed(kmh: Int) {
        val clamped = kmh.coerceIn(1, 40)
        cachedMaxSpeedKmh = clamped
        val frame = ControlFrames.modeStandStop(clamped)
        // Direkter Write, NICHT durch die Verify-Logik (kein Phantom-Park-Pending
        // auf Dashboard/Widget/Tile). Das Frame ist ein Stand-Mode-Frame und
        // deaktiviert physisch das Parken — Latch entsprechend auf "entparkt".
        manager.writeLowFrame(frame, label = "set-maxspeed-$clamped")
        parkController.notePark(false)
        scope.launch { settings.setGearLimits(g1 = 6, g2 = 15, g3 = clamped) }
    }

    fun setBlinkerton(on: Boolean) = manager.sendAt(AtCommands.setVoiceOff(on))
}
