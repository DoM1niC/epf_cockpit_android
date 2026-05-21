package eu.dns3d.epfcockpit.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend

/**
 * Wraps the GATT exchange with a single scooter. Exposes telemetry and AT replies
 * as flows; raises errors via the connection state.
 *
 * Lifecycle is owned by [BleRepository]; do not instantiate elsewhere.
 */
class ScooterManager(context: Context) : BleManager(context) {

    private val tag = "ScooterManager"

    private var lowTx: android.bluetooth.BluetoothGattCharacteristic? = null
    private var lowRx: android.bluetooth.BluetoothGattCharacteristic? = null
    private var highTx: android.bluetooth.BluetoothGattCharacteristic? = null
    private var highRx: android.bluetooth.BluetoothGattCharacteristic? = null

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _atReplies = MutableSharedFlow<AtResponse>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val atReplies = _atReplies.asSharedFlow()

    private val _rawFrames = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawFrames = _rawFrames.asSharedFlow()

    override fun getMinLogPriority() = Log.VERBOSE
    override fun log(priority: Int, message: String) { Log.println(priority, tag, message) }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val low = gatt.getService(BleConstants.LOW_SERVICE) ?: return false
        val high = gatt.getService(BleConstants.HIGH_SERVICE) ?: return false
        lowTx = low.getCharacteristic(BleConstants.LOW_TX_CHAR)
        lowRx = low.getCharacteristic(BleConstants.LOW_RX_CHAR)
        highTx = high.getCharacteristic(BleConstants.HIGH_TX_CHAR)
        highRx = high.getCharacteristic(BleConstants.HIGH_RX_CHAR)
        return lowTx != null && lowRx != null && highTx != null && highRx != null
    }

    override fun initialize() {
        requestMtu(247).enqueue()

        setNotificationCallback(lowRx).with { _, data ->
            val bytes = data.value ?: return@with
            _rawFrames.tryEmit(bytes)
            TelemetryDecoder.decode(bytes)?.let { _telemetry.value = it }
        }
        enableNotifications(lowRx).enqueue()

        setNotificationCallback(highRx).with { _, data ->
            val bytes = data.value ?: return@with
            _atReplies.tryEmit(AtCommands.parseResponse(bytes))
        }
        enableNotifications(highRx).enqueue()
    }

    override fun onServicesInvalidated() {
        lowTx = null; lowRx = null; highTx = null; highRx = null
        _telemetry.value = null
    }

    suspend fun connectTo(device: BluetoothDevice) {
        connect(device)
            .retry(3, 200)
            .useAutoConnect(true)
            .timeout(15_000)
            .suspend()
    }

    suspend fun sendControl(state: ControlState) {
        val char = lowTx ?: return
        writeCharacteristic(char, Data(ControlFrames.build(state)),
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
    }

    suspend fun sendAt(payload: ByteArray) {
        val char = highTx ?: return
        writeCharacteristic(char, Data(payload),
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
    }

    suspend fun authenticate(pin: String) = sendAt(AtCommands.verifyPin(pin))
    suspend fun changePin(newPin: String) = sendAt(AtCommands.changePin(newPin))

    fun disconnectAndRelease() {
        disconnect().enqueue()
        close()
    }
}
