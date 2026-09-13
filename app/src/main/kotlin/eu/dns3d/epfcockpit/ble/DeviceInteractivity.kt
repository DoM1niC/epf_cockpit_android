package eu.dns3d.epfcockpit.ble

import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Bildschirm-/Sperr-Zustand des Handys. Trennt die zwei Fälle, die der
 * ProcessLifecycle beide als ON_STOP meldet:
 *   - Handy gesperrt / Display aus → der User kann gerade KEINE andere App
 *     (z.B. die Hersteller-App) bedienen, ein Hintergrund-Reconnect stört niemanden.
 *   - Display an + entsperrt + andere App vorne → möglicher Single-Link-Handover
 *     (Commit 360c8d4), dort darf die App den Roller nicht wegschnappen.
 *
 * Der Zustand wird immer frisch gezogen; der Receiver liefert nur den Anstoß
 * zur Neubewertung ([onChange], mit true bei einem Bluetooth-Zustandswechsel).
 */
internal class DeviceInteractivity(
    context: Context,
    private val onChange: (bluetoothStateChanged: Boolean) -> Unit,
) {

    private val pm = context.getSystemService(PowerManager::class.java)
    private val km = context.getSystemService(KeyguardManager::class.java)

    /** Display an (AOD zählt laut AOSP als nicht interaktiv). */
    fun isInteractive(): Boolean = pm?.isInteractive ?: true

    /** Display an UND entsperrt → der User KANN gerade eine andere App bedienen. */
    fun handsOn(): Boolean = isInteractive() && km?.isKeyguardLocked != true

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // Ein fremder Link zum Roller endet → sofort neu bewerten (sonst stünde
            // der Recheck-Timer im CPU-Suspend). Zustellung braucht BLUETOOTH_CONNECT.
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // RECEIVER_EXPORTED ist hier Pflicht: USER_PRESENT sendet SystemUI (eigene
        // App-UID, nicht SYSTEM_UID) — an NOT_EXPORTED-Receiver wird es verworfen,
        // Entsperren würde dann nie erkannt. Alle Actions sind geschützte
        // System-Broadcasts, die keine Dritt-App senden kann.
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val bt = intent.action == BluetoothAdapter.ACTION_STATE_CHANGED
                    Log.d(TAG, "${intent.action} handsOn=${handsOn()}")
                    onChange(bt)
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "DeviceInteractivity"
    }
}
