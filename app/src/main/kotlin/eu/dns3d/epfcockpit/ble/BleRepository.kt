package eu.dns3d.epfcockpit.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import javax.inject.Inject
import javax.inject.Singleton

/** Stable summary of a scooter we have seen during scanning. */
data class DiscoveredScooter(
    val address: String,
    val name: String?,
    val rssi: Int,
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
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val manager = ScooterManager(context)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    val telemetry: StateFlow<Telemetry?> = manager.telemetry
    val atReplies: SharedFlow<AtResponse> = manager.atReplies
    val rawFrames: SharedFlow<ByteArray> = manager.rawFrames

    init {
        scope.launch {
            manager.atReplies.collect { reply ->
                when (reply) {
                    AtResponse.PinOk -> _connection.update { current ->
                        if (current is ConnectionState.Connected) current.copy(authenticated = true)
                        else current
                    }
                    AtResponse.PinRejected -> _connection.update { current ->
                        if (current is ConnectionState.Connected) current.copy(authenticated = false)
                        else current
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Cold flow of scan results — collect to start scanning, cancel to stop. */
    fun scan(): kotlinx.coroutines.flow.Flow<DiscoveredScooter> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareBatchingIfSupported(false)
            .build()

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
                val isLikelyScooter = name != null && BleConstants.ADVERTISED_NAME_PREFIXES
                    .any { name.startsWith(it, ignoreCase = true) }
                if (!isLikelyScooter) return
                trySend(DiscoveredScooter(result.device.address, name, result.rssi))
            }
        }
        scanner.startScan(emptyList(), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    suspend fun connect(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            _connection.value = ConnectionState.Failed("Bluetooth not available")
            return
        }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        _connection.value = ConnectionState.Connecting(address)
        runCatching { manager.connectTo(device) }
            .onSuccess { _connection.value = ConnectionState.Connected(address, false) }
            .onFailure { _connection.value = ConnectionState.Failed(it.message ?: "unknown") }
    }

    suspend fun authenticate(pin: String) = manager.authenticate(pin)
    suspend fun changePin(newPin: String) = manager.changePin(newPin)
    suspend fun sendControl(state: ControlState) = manager.sendControl(state)

    fun disconnect() {
        manager.disconnectAndRelease()
        _connection.value = ConnectionState.Disconnected
    }
}
