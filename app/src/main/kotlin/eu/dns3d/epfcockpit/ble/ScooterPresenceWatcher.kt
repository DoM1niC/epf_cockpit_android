package eu.dns3d.epfcockpit.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * "Ist der Roller wieder da?"-Sentinel für den Hintergrund-Reconnect bei
 * gesperrtem Handy: ein nackter GATT-Client mit autoConnect=true. Der
 * Bluetooth-Controller wartet damit selbst (Background-Connect), bis der
 * Roller wieder advertised — ohne App-CPU, ohne Timer (die im CPU-Suspend
 * stehen) und ohne Scan (ungefilterte Scans setzt Android bei Display aus aus).
 *
 * Er meldet nur [onPresent]; die eigentliche Session (Nordic-Manager, PIN,
 * Heartbeat) baut [BleRepository] danach wie gewohnt per Direct-Connect auf
 * und schließt den Sentinel wieder. Nordic selbst bleibt bei
 * useAutoConnect(false), damit dessen Reconnect-Semantik unverändert bleibt.
 *
 * Achtung: der Stack meldet einem Background-Connect-Client JEDEN Link zum
 * Roller — auch einen, den eine andere App (Hersteller-App) aufgebaut hat.
 * [BleRepository] prüft deshalb vor der Übernahme, ob der Link fremd ist.
 */
internal class ScooterPresenceWatcher(
    private val context: Context,
    private val onPresent: () -> Unit,
    /** Sentinel wurde wegen eines Registrierungsfehlers verworfen → neu armieren lassen. */
    private val onDiscarded: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var armedAddress: String? = null
    private var connectedOnce = false

    @get:Synchronized
    val isArmed: Boolean
        get() = gatt != null

    @Synchronized
    @SuppressLint("MissingPermission")
    fun arm(address: String) {
        if (gatt != null && armedAddress.equals(address, ignoreCase = true)) return
        disarm()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        connectedOnce = false
        gatt = runCatching {
            val device = adapter.getRemoteDevice(address)
            if (Build.VERSION.SDK_INT >= 37) {
                val connectionSettings = BluetoothGattConnectionSettings.Builder()
                    .setAutoConnectEnabled(true)
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .build()
                device.connectGatt(connectionSettings, ContextCompat.getMainExecutor(context), callback)
            } else {
                // Callbacks kommen auf einem Binder-Thread — der Callback ist synchronisiert.
                @Suppress("DEPRECATION")
                device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }.onFailure { Log.w(TAG, "presence arm failed: ${it.message}") }.getOrNull()
        armedAddress = if (gatt != null) address else null
        Log.i(TAG, "presence armed addr=$address ok=${gatt != null}")
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun disarm() {
        val g = gatt ?: return
        gatt = null
        armedAddress = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
        Log.i(TAG, "presence disarmed")
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            var discarded = false
            val present = synchronized(this@ScooterPresenceWatcher) {
                if (g !== gatt) return
                Log.i(TAG, "presence status=$status state=$newState")
                when {
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        connectedOnce = true
                        true
                    }
                    // Background-Connect konnte gar nicht registriert werden (z.B.
                    // Accept-List voll, status 133): Client verwerfen, damit die
                    // Episode beim nächsten Durchlauf frisch armiert statt ewig
                    // auf einen toten Sentinel zu warten.
                    status != BluetoothGatt.GATT_SUCCESS && !connectedOnce -> {
                        gatt = null
                        armedAddress = null
                        runCatching { g.close() }
                        discarded = true
                        false
                    }
                    else -> false
                }
            }
            if (present) onPresent()
            if (discarded) onDiscarded()
        }
    }

    private companion object {
        const val TAG = "ScooterPresence"
    }
}
