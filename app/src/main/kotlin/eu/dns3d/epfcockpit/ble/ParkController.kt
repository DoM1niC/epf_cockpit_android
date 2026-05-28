package eu.dns3d.epfcockpit.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Phase einer Park-/Mode-Aktion. Macht den "konnte nicht bestätigt"-Zustand
 * erstklassig, sodass auch Widget/Quick-Tile/Notification eine Pending-/Fail-
 * Anzeige bekommen können, ohne eigene Logik zu erfinden.
 */
sealed interface ParkPhase {
    /** Nichts in Arbeit — der angezeigte Wert spiegelt live die Telemetrie. */
    data object Idle : ParkPhase
    /** Frame(s) gesendet, warten dass das Telemetrie-Bit `target` erreicht. */
    data class Pending(val target: Boolean, val attempt: Int) : ParkPhase
    /** Telemetrie hat den gewünschten Zustand bestätigt. */
    data class Confirmed(val value: Boolean) : ParkPhase
    /** Nach allen Retries nicht bestätigt — Anzeige fällt auf den echten Wert zurück. */
    data class Failed(val target: Boolean, val observed: Boolean) : ParkPhase
}

/**
 * Verify-after-write für den Park-/Mode-Zustand des Pulse-Plus-Controllers.
 *
 * Hintergrund: Steuerframes gehen fire-and-forget mit WRITE_TYPE_NO_RESPONSE
 * raus — es gibt KEINE Bestätigung, dass der Controller ein Frame angewendet
 * hat. Geht ein Park-Frame verloren, blieb die UI früher auf dem optimistischen
 * Wert hängen, und der nächste Tap invertierte diese Lüge → der User musste
 * doppelt schalten.
 *
 * Die einzige verlässliche Rückmeldung ist das Telemetrie-Statusbyte [22] Bit 5
 * (0x20), das ~alle 600 ms streamt. Dieser Controller:
 *   1. setzt das Frame ab,
 *   2. wartet bis ein FRISCHES Telemetrie-Frame `target` zeigt (bis [PER_ATTEMPT_MS]),
 *   3. wiederholt das Frame bei Timeout (bis [MAX_ATTEMPTS] gesamt),
 *   4. landet in Confirmed oder Failed — in BEIDEN Fällen zeigt die UI danach
 *      den ECHTEN Telemetrie-Wert, nie eine Lüge.
 *
 * NUR der Park-Toggle ([BleRepository.setParking]) läuft hier durch und
 * surface't Pending/Failed an die Park-UI. Mode-/Max-Speed-Frames flippen das
 * Park-Bit zwar ebenfalls (0xA2 parkt, 0x82 entparkt), werden aber bewusst NICHT
 * hier verifiziert — sonst würde ein Mode-/Speed-Befehl Spinner + Fehlertext am
 * falschen Control (dem Park-Switch) auslösen. Ihr Park-Nebeneffekt erscheint
 * trotzdem korrekt über den live Telemetrie-Mirror im Idle-Zustand.
 */
class ParkController(
    private val scope: CoroutineScope,
    private val telemetry: StateFlow<Telemetry?>,
    private val writeFrame: (frame: ByteArray, label: String) -> Unit,
) {
    private val _phase = MutableStateFlow<ParkPhase>(ParkPhase.Idle)
    val phase: StateFlow<ParkPhase> = _phase.asStateFlow()

    /**
     * Der Wert den JEDE Oberfläche (Dashboard, Tile, Widget, Service) anzeigt:
     *   - Pending   → optimistisches Ziel (Switch springt nicht sichtbar)
     *   - Confirmed → bestätigter Wert
     *   - Failed    → echter beobachteter Wert (genau das killt den Doppel-Tap)
     *   - Idle      → live Telemetrie-Bit
     * `null` bis zum ersten Telemetrie-Frame / nach Disconnect — die UI zeigt
     * dann "unbekannt" statt eines erfundenen `false`.
     */
    val parkingDisplay: StateFlow<Boolean?> =
        combine(_phase, telemetry) { p, t ->
            when (p) {
                is ParkPhase.Pending -> p.target
                is ParkPhase.Confirmed -> p.value
                // Failed: bevorzugt die LIVE-Telemetrie — falls das Frame doch
                // noch spät griff (BLE-Stau, knapp außerhalb des 900ms-Fensters),
                // zeigt der Switch sofort den echten Wert statt 3s lang den beim
                // Timeout eingefrorenen observed-Snapshot.
                is ParkPhase.Failed -> t?.parking ?: p.observed
                ParkPhase.Idle -> t?.parking
            }
        }.stateIn(scope, SharingStarted.Eagerly, telemetry.value?.parking)

    private var job: Job? = null
    private var settleJob: Job? = null

    /**
     * Park/Mode auf [target] setzen und gegen die Telemetrie verifizieren.
     * Storniert eine laufende Verifikation (letzte Aktion gewinnt) — Nordic
     * serialisiert die GATT-Writes ohnehin, das hier killt nur die alte
     * Verify-Coroutine, damit nicht zwei Loops auf _phase rennen.
     */
    fun request(target: Boolean, frame: ByteArray, label: String) {
        job?.cancel()
        settleJob?.cancel()
        job = scope.launch { verify(target, frame, label) }
    }

    /** Vom Disconnect-Observer aufgerufen — nie ein Pending über einen Link-Verlust leaken. */
    fun onDisconnected() {
        job?.cancel()
        settleJob?.cancel()
        _phase.value = ParkPhase.Idle
    }

    private suspend fun verify(target: Boolean, frame: ByteArray, label: String) {
        repeat(MAX_ATTEMPTS) { i ->
            // Liveness ZUERST prüfen, bevor wir Pending schreiben. _phase-Writes
            // laufen auf Dispatchers.Default (Repo-Scope ohne Dispatcher), also
            // potenziell auf einem anderen Thread als ein paralleles
            // onDisconnected()/request(). Ohne diesen Guard könnte die Schleife
            // nach einem cancel + _phase=Idle noch ein Phantom-Pending setzen,
            // das den Disconnect überlebt.
            coroutineContext.ensureActive()
            _phase.value = ParkPhase.Pending(target, attempt = i + 1)
            // Aktuelles Frame VOR dem Write festhalten. StateFlow re-emittiert
            // seinen aktuellen Wert an neue Collector — ohne diesen Guard würde
            // .first{} sofort gegen das alte Pre-Write-Frame "bestätigen", wenn
            // target zufällig schon dem alten Bit entspricht (= der Lost-Write-
            // Fall). dropWhile{ it === baseline } erzwingt ein FRISCHES Frame.
            val baseline = telemetry.value
            writeFrame(frame, label)
            val confirmed = withTimeoutOrNull(PER_ATTEMPT_MS) {
                telemetry.filterNotNull()
                    .dropWhile { it === baseline }
                    .first { it.parking == target }
            }
            // Falls wir genau jetzt von einer neueren request() storniert wurden,
            // kein veraltetes Confirmed mehr schreiben.
            coroutineContext.ensureActive()
            if (confirmed != null) {
                _phase.value = ParkPhase.Confirmed(target)
                scheduleSettle(ParkPhase.Confirmed(target), SETTLE_MS)
                return
            }
            Log.w(TAG, "park verify timeout attempt=${i + 1}/$MAX_ATTEMPTS target=$target label=$label")
        }
        coroutineContext.ensureActive()
        val observed = telemetry.value?.parking ?: target
        Log.w(TAG, "park NOT confirmed after $MAX_ATTEMPTS attempts target=$target observed=$observed")
        _phase.value = ParkPhase.Failed(target, observed)
        scheduleSettle(ParkPhase.Failed(target, observed), FAILED_LINGER_MS)
    }

    /**
     * Confirmed/Failed nach kurzer Zeit zurück auf Idle, sodass wieder die live
     * Telemetrie die Anzeige treibt. SEPARATE Coroutine + Identitäts-Check,
     * damit ein superseding request() (das schon ein frisches Pending gesetzt
     * hat) nicht überschrieben wird.
     */
    private fun scheduleSettle(expected: ParkPhase, afterMs: Long) {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(afterMs)
            if (_phase.value == expected) _phase.value = ParkPhase.Idle
        }
    }

    private companion object {
        const val TAG = "ParkController"
        /** Fenster pro Versuch ≈ 1.5 Telemetrie-Zyklen (Telemetrie ~600 ms). */
        const val PER_ATTEMPT_MS = 900L
        /** 1 initialer Write + 2 Retries — deckt zwei unabhängige Drops ab. */
        const val MAX_ATTEMPTS = 3
        /** Kurzes Confirmed-Fenster, dann zurück auf live Telemetrie. */
        const val SETTLE_MS = 400L
        /** Failed bleibt länger sichtbar, damit die UI den Fehler zeigen kann. */
        const val FAILED_LINGER_MS = 3_000L
    }
}
