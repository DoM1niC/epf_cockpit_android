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
            .retry(2, 200)
            // useAutoConnect(true) silently waits in passive-scan mode for
            // the device to advertise — sparing but slow (multiple seconds
            // even when the device is right there). False uses an active
            // direct connect attempt, which returns within ~1 s if the MAC
            // is reachable and fails fast otherwise so we can scan-recover.
            .useAutoConnect(false)
            .timeout(6_000)
            .suspend()
    }

    /** Raw write to the low-TX char — used by both sendControl and the heartbeat. */
    fun writeLowFrame(frame: ByteArray, label: String = "raw") {
        val char = lowTx ?: run {
            Log.w(tag, "writeLowFrame[$label]: lowTx is null (not connected?)")
            return
        }
        Log.v(tag, "writeLowFrame[$label]: ${frame.toHex()}")
        writeCharacteristic(
            char, Data(frame),
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        )
            .fail { _, status -> Log.w(tag, "writeLowFrame[$label]: fail status=$status") }
            .enqueue()
    }

    fun sendControl(state: ControlState) {
        val char = lowTx ?: run {
            Log.w(tag, "sendControl: lowTx is null (not connected?)")
            return
        }
        val frame = ControlFrames.build(state)
        Log.i(tag, "sendControl: ${frame.toHex()} state=$state")
        // Fire-and-forget enqueue is the canonical Nordic pattern. The library
        // serialises requests internally on its own thread, so two rapid UI
        // toggles can't interleave. We don't use `.suspend()` because that
        // returned-once-dispatched semantics turned out to wedge the queue
        // for us when the scooter doesn't acknowledge the write.
        writeCharacteristic(
            char, Data(frame),
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        )
            .done { Log.d(tag, "sendControl: done") }
            .fail { _, status -> Log.w(tag, "sendControl: fail status=$status") }
            .enqueue()
    }

    fun sendAt(payload: ByteArray) {
        val char = highTx ?: run {
            Log.w(tag, "sendAt: highTx is null (not connected?)")
            return
        }
        Log.i(tag, "sendAt: ${payload.toString(Charsets.US_ASCII)}")
        writeCharacteristic(
            char, Data(payload),
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
            .done { Log.d(tag, "sendAt: done") }
            .fail { _, status -> Log.w(tag, "sendAt: fail status=$status") }
            .enqueue()
    }

    fun authenticate(pin: String) = sendAt(AtCommands.verifyPin(pin))
    fun changePin(newPin: String) = sendAt(AtCommands.changePin(newPin))

    /**
     * Tear down the live GATT connection but keep the BleManager instance
     * reusable. `close()` would invalidate the manager — once called, the
     * library refuses any further connect() and silently no-ops, which made
     * "Trennen → Scan → Verbinden" a dead end. The manager is released for
     * good only when the Application process dies.
     */
    fun requestDisconnect() {
        disconnect().enqueue()
    }
}
