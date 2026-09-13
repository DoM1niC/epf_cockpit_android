package eu.dns3d.epfcockpit.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.DiscoveredScooter
import eu.dns3d.epfcockpit.ble.ReconnectStatus
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ScanUiState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredScooter> = emptyList(),
    val rememberedAddress: String? = null,
    val rememberedName: String? = null,
    val autoReconnecting: Boolean = false,
    val showAllDevices: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val permissionsGranted: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val ble: BleRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    val connection: StateFlow<ConnectionState> = ble.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    private var scanJob: Job? = null
    private var rememberedAutoConnect = false

    // Guard against the UI's ON_RESUME / permission callback racing the
    // init coroutine. Without this, refreshEnvironment() can fire before
    // settings.first() returns — at that point rememberedAutoConnect is
    // still false and we end up in startScan() instead of startAutoReconnect().
    private var ready = false

    init {
        viewModelScope.launch {
            val snap = settings.flow.first()
            _state.update {
                it.copy(
                    rememberedAddress = snap.lastScooterAddress,
                    rememberedName = snap.lastScooterName,
                )
            }
            // Don't auto-reconnect if the user just hit Trennen during this
            // process — they wouldn't have pressed it if they wanted to be
            // immediately reconnected. The flag clears on app restart.
            rememberedAutoConnect = snap.autoConnect &&
                snap.lastScooterAddress != null &&
                !ble.userDisconnected
            ready = true
            refreshEnvironment()
        }
    }

    /** Should be called when the permission state changes from the UI. */
    fun onPermissionsResult(granted: Boolean) {
        _state.update { it.copy(permissionsGranted = granted) }
        if (granted) refreshEnvironment()
    }

    /** Re-evaluate BT-enabled state (e.g. after the system enable dialog closes). */
    fun refreshEnvironment() {
        if (!ready) return  // settings not loaded yet — init will call us back
        val btOn = ble.isBluetoothEnabled()
        _state.update { it.copy(bluetoothEnabled = btOn) }
        if (!_state.value.permissionsGranted || !btOn) {
            stopScan()
            return
        }
        // Never restart anything while we're already mid-flight or already
        // talking to a scooter — refreshEnvironment is wired to ON_RESUME and
        // will otherwise race with the in-flight scan.
        if (connection.value !is ConnectionState.Disconnected) return
        // Eine Reconnect-Episode im Repository läuft schon — kein zweiter Connect
        // parallel (deren Timeouts setzen kurz Disconnected).
        if (ble.reconnectStatus.value !is ReconnectStatus.Idle) return
        if (_state.value.autoReconnecting || _state.value.scanning) return

        if (rememberedAutoConnect) {
            rememberedAutoConnect = false
            startAutoReconnect()
        } else {
            startScan()
        }
    }

    /**
     * Two-phase auto-reconnect:
     *   Phase 1 (≤6 s): direct connect on the stored MAC. Fastest happy path
     *                   when the privacy address hasn't rotated.
     *   Phase 2 (≤15 s): scan for the saved device name (privacy MAC may
     *                   have changed); reconnect with the freshly-seen MAC.
     * Falls back to a visible scan if both phases fail.
     */
    private fun startAutoReconnect() {
        val savedName = _state.value.rememberedName
        val savedAddr = _state.value.rememberedAddress ?: return
        _state.update { it.copy(autoReconnecting = true, devices = emptyList(), error = null) }
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            // Phase 1 — direct connect on the stored MAC.
            try {
                withTimeoutOrNull(6_000L) { ble.connect(savedAddr) }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(autoReconnecting = false) }
                throw kotlinx.coroutines.CancellationException()
            } catch (_: Throwable) { /* fall through to phase 2 */ }

            if (connection.value is ConnectionState.Connected) {
                _state.update { it.copy(autoReconnecting = false) }
                return@launch
            }

            // Phase 2 — scan by name, then connect with the fresh address.
            val match = try {
                withTimeoutOrNull(15_000L) {
                    ble.scan(scootersOnly = true).firstOrNull { found ->
                        (!savedName.isNullOrBlank() &&
                                found.name?.equals(savedName, ignoreCase = true) == true) ||
                                found.address.equals(savedAddr, ignoreCase = true)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(autoReconnecting = false) }
                throw kotlinx.coroutines.CancellationException()
            }

            if (match != null) {
                settings.rememberScooter(match.address, match.name)
                runCatching { ble.connect(match.address) }
            }
            _state.update { it.copy(autoReconnecting = false) }
            if (connection.value !is ConnectionState.Connected) {
                // Nothing worked — drop the user into the manual scan view.
                startScan()
            }
        }
    }

    fun toggleShowAll() {
        _state.update { it.copy(showAllDevices = !it.showAllDevices, devices = emptyList()) }
        if (_state.value.scanning) {
            stopScan(); startScan()
        }
    }

    fun startScan() {
        if (!_state.value.permissionsGranted || !_state.value.bluetoothEnabled) return
        if (_state.value.autoReconnecting) return
        // If we're still mid-connection, free the BLE radio first — the
        // Nordic manager throws a CancellationException if a new scan
        // starts while a GATT connect is in flight on the same adapter.
        if (connection.value !is ConnectionState.Disconnected) {
            ble.disconnect()
        }
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        val scootersOnly = !_state.value.showAllDevices
        scanJob = viewModelScope.launch {
            try {
                ble.scan(scootersOnly = scootersOnly).collect { found ->
                    _state.update { current ->
                        // Stable list: an existing device gets its rssi/name
                        // updated in place, a new device is inserted by RSSI
                        // ONCE. We never resort the full list on every tick —
                        // otherwise the rows jump around and the user can't
                        // tap reliably.
                        val existingIdx = current.devices.indexOfFirst { it.address == found.address }
                        val next = if (existingIdx >= 0) {
                            current.devices.toMutableList().also { it[existingIdx] = found }
                        } else {
                            val insertAt = current.devices.indexOfFirst {
                                (!it.likelyScooter && found.likelyScooter) ||
                                        (it.likelyScooter == found.likelyScooter && it.rssi < found.rssi)
                            }.let { if (it < 0) current.devices.size else it }
                            current.devices.toMutableList().also { it.add(insertAt, found) }
                        }
                        current.copy(devices = next)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Cooperative cancel (user pressed Stop, or auto-reconnect
                // took over). NOT an error — just stop scanning silently.
                throw ce
            } catch (e: Throwable) {
                _state.update { it.copy(scanning = false, error = e.message) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun connect(device: DiscoveredScooter) {
        viewModelScope.launch {
            stopScan()
            settings.rememberScooter(device.address, device.name)
            ble.connect(device.address)
        }
    }
}
