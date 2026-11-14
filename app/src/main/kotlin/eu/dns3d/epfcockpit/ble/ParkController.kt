package eu.dns3d.epfcockpit.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Park-Zustand des Pulse-Plus-Controllers — INTENT-basiert.
 *
 * Hardware-Eigenheit (empirisch bestätigt durch Tests am echten Roller): das
 * Telemetrie-Statusbyte Bit 5 ist KEIN verlässlicher Park-Indikator. Es gibt
 * drei Frames für drei Zustände:
 *
 *   0x22  Parking ON   → geparkt          (Telemetrie Bit 5 = 1)
 *   0x82  Kick-Start   → entparkt, Kick   (Telemetrie Bit 5 = 0)
 *   0xA2  Stand-Stop   → entparkt, Stand  (Telemetrie Bit 5 = 1)
 *
 * Bit 5 = 1 bedeutet also "geparkt ODER Stand-Mode" — mehrdeutig. Ein Verify
 * gegen Bit 5 würde Stand-Mode fälschlich als geparkt sehen (und umgekehrt).
 * Deshalb tracken wir den Park-Zustand als INTENT (was zuletzt kommandiert
 * wurde), nicht aus der Telemetrie.
 *
 * Gegen verlorene fire-and-forget-Writes (WRITE_TYPE_NO_RESPONSE, kein Ack)
 * senden wir jedes Park-Kommando mehrfach (blind retry). Das war die Ursache
 * des ursprünglichen Doppel-Tap-Problems — ein einzelnes verlorenes Frame ließ
 * App-Anzeige und Roller auseinanderlaufen.
 */
class ParkController(
    private val scope: CoroutineScope,
    private val telemetry: StateFlow<Telemetry?>,
    private val writeFrame: (frame: ByteArray, label: String) -> Unit,
) {
    private val _latch = MutableStateFlow<Boolean?>(null)

    /**
     * Angezeigter Park-Zustand für JEDE Oberfläche (Dashboard, Tile, Widget,
     * Service). Intent-Latch: gesetzt durch Park-Toggle und durch
     * Mode-Seiteneffekte ([notePark]). `null` vor dem ersten Telemetrie-Frame /
     * nach Disconnect — die UI zeigt dann "unbekannt" statt eines erfundenen Werts.
     */
    val parkingDisplay: StateFlow<Boolean?> = _latch.asStateFlow()

    private var sendJob: Job? = null

    init {
        // Initial-Seed aus dem ersten Telemetrie-Frame: [Telemetry.parked] löst
        // die Mehrdeutigkeit von Bit 5 über das "entsperrt"-Bit des Statusworts
        // auf. Danach führt der Intent (Kommandos) — die Telemetrie zeigt beim
        // Umschalten kurz Zwischenzustände. Re-seedet auf Reconnect (Latch wird
        // bei Disconnect null, Telemetrie wird genullt → nächstes Frame seedet neu).
        scope.launch {
            telemetry.filterNotNull().collect { t ->
                if (_latch.value == null) {
                    Log.i(TAG, "park seed: status=0x%04X → parked=%b".format(t.statusWord, t.parked))
                    _latch.value = t.parked
                }
            }
        }
    }

    /**
     * Park-Kommando absetzen: Latch sofort auf [target] (optimistisch), das Frame
     * mehrfach senden (blind retry gegen verlorene NO_RESPONSE-Writes).
     *
     * [frame] wird pro Resend NEU gebaut: das Statusframe trägt den kompletten
     * Soll-Zustand (inkl. Licht). Schaltet der User zwischen zwei Resends das
     * Licht um, darf der nächste Resend den alten Licht-Zustand nicht
     * zurückschreiben.
     */
    fun setParking(target: Boolean, label: String, frame: () -> ByteArray) {
        _latch.value = target
        sendJob?.cancel()
        sendJob = scope.launch {
            repeat(RESENDS) { i ->
                writeFrame(frame(), label)
                if (i < RESENDS - 1) delay(RESEND_GAP_MS)
            }
            Log.d(TAG, "park frame sent ${RESENDS}x: $label target=$target")
        }
    }

    /**
     * Park-Latch direkt setzen, ohne eigenen Frame-Send. Für bekannte
     * Seiteneffekte anderer Kommandos: ein Mode-/Speed-Frame (Stand/Kick)
     * entparkt physisch, also soll der Park-Switch sofort auf "entparkt"
     * mitziehen. Den Mode-Frame selbst sendet der Aufrufer.
     */
    fun notePark(value: Boolean) {
        _latch.value = value
    }

    /** Vom Disconnect-Observer: Latch invalidieren, damit der nächste Scooter neu geseedet wird. */
    fun onDisconnected() {
        sendJob?.cancel()
        _latch.value = null
    }

    private companion object {
        const val TAG = "ParkController"
        /** Wie oft ein Park-Frame gesendet wird (gegen verlorene NO_RESPONSE-Writes). */
        const val RESENDS = 3
        const val RESEND_GAP_MS = 150L
    }
}
