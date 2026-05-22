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

    init {
        // Connection-Lifecycle vom Nordic-Manager beobachten — sonst bleibt
        // _connection auf "Connected" hängen wenn der Scooter ausgeht oder
        // außer Reichweite fährt, weil der suspend connect() längst returnt
        // hat und niemand mehr zuhört.
        scope.launch {
            manager.connEvents.collect { event ->
                when (event) {
                    is ScooterManager.ConnEvent.Disconnected -> {
                        stopHeartbeat()
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

            Log.i(TAG, "auto-reconnect phase 1: direct connect to $savedAddr")
            // Phase 1: direkter Connect auf alte MAC
            try {
                withTimeoutOrNull(6_000L) { connect(savedAddr) }
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (t: Throwable) {
                Log.w(TAG, "auto-reconnect phase 1 failed: ${t.message}")
            }
            if (_connection.value is ConnectionState.Connected) return@launch

            // Phase 2: Scan-by-Name
            Log.i(TAG, "auto-reconnect phase 2: scan by name '$savedName'")
            val match = try {
                withTimeoutOrNull(15_000L) {
                    scan(scootersOnly = true).firstOrNull { found ->
                        (!savedName.isNullOrBlank() &&
                            found.name?.equals(savedName, ignoreCase = true) == true) ||
                            found.address.equals(savedAddr, ignoreCase = true)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            }
            if (match != null) {
                settings.rememberScooter(match.address, match.name)
                runCatching { connect(match.address) }
            } else {
                Log.i(TAG, "auto-reconnect phase 2: nothing found")
            }
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

    fun setParking(on: Boolean) {
        val frame = if (on) ControlFrames.PARKING_ON else ControlFrames.PARKING_OFF
        manager.writeLowFrame(frame, label = if (on) "park-on" else "park-off")
    }

    /**
     * Ride mode toggle. `stand=true` → Stand-Stop (must be pushed before
     * motor engages, scooter also parks). `stand=false` → Kick-Start
     * (goes on throttle alone, scooter also unparks).
     * NB: this overlaps with [setParking] because the official app uses the
     * same frames for both — switching modes also flips the park state.
     */
    fun setRideMode(stand: Boolean) {
        val frame = if (stand) ControlFrames.MODE_STAND_STOP else ControlFrames.MODE_KICK_START
        manager.writeLowFrame(frame, label = if (stand) "mode-stand" else "mode-kick")
    }

    fun setBlinkerton(on: Boolean) = manager.sendAt(AtCommands.setVoiceOff(on))
}
