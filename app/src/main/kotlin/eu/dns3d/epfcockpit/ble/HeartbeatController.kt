package eu.dns3d.epfcockpit.ble

import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Init-Burst und 600-ms-Heartbeat einer authentifizierten Session — aus dem
 * [BleRepository] herausgelöst, Verhalten unverändert.
 *
 * Ohne Heartbeat streamt der Roller keine Telemetrie und kappt nach ~30 s.
 * delay() läuft auf der Uptime-Uhr, die im CPU-Suspend steht — wurde einmal
 * eine Lücke > [STARVATION_MS] gemessen (im Loop oder beim Abriss), hält der
 * Loop ab dann bei Display aus eine Partial-WakeLock (adaptiv: nur Geräte, die
 * es brauchen).
 *
 * Start (AT-Antwort) und Stop (Connection-Event) kommen von verschiedenen
 * Threads; [lock] schützt die Jobs.
 */
internal class HeartbeatController(
    private val scope: CoroutineScope,
    /** Non-refcounted Partial-WakeLock oder null, wenn kein PowerManager verfügbar ist. */
    private val wakeLock: PowerManager.WakeLock?,
    private val writeFrame: (frame: ByteArray, label: String) -> Unit,
    private val isManagerConnected: () -> Boolean,
    private val isStateConnected: () -> Boolean,
    private val isScreenOn: () -> Boolean,
) {
    private val lock = Any()
    private var heartbeatJob: Job? = null
    private var initBurstJob: Job? = null
    @Volatile private var starved = false
    @Volatile private var lastTickElapsed = 0L

    /**
     * Nach PinOk: Init-Burst (5× PULSE_INIT) und danach der Heartbeat — exakt
     * wie die offizielle App. Der Burst-Job wird gehalten, damit ein Abriss
     * währenddessen keinen verwaisten Heartbeat auf dem toten Link startet.
     */
    fun startAfterInitBurst() {
        synchronized(lock) {
            initBurstJob?.cancel()
            initBurstJob = scope.launch {
                repeat(5) {
                    writeFrame(ControlFrames.PULSE_INIT, "init")
                    delay(50)
                }
                start()
            }
        }
    }

    private fun start() {
        synchronized(lock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            if (!isManagerConnected() || !isStateConnected()) {
                Log.i(TAG, "heartbeat nicht gestartet — Link bereits weg")
                return
            }
            lastTickElapsed = SystemClock.elapsedRealtime()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext()[Job]
                var acquiredAt = 0L
                try {
                    var last = SystemClock.elapsedRealtime()
                    while (isActive) {
                        if (!isStateConnected()) break
                        writeFrame(ControlFrames.PULSE_HEARTBEAT, "hb")
                        val now = SystemClock.elapsedRealtime()
                        lastTickElapsed = now
                        val wl = wakeLock
                        if (wl != null) {
                            val needLock = starved && !isScreenOn()
                            // Vor Ablauf erneuern (non-refcounted acquire setzt nur den Timeout
                            // zurück) — sonst fiele der Schutz jede Stunde kurz weg.
                            if (needLock && (!wl.isHeld || now - acquiredAt > WAKE_TIMEOUT_MS - WAKE_RENEW_MARGIN_MS)) {
                                wl.acquire(WAKE_TIMEOUT_MS)
                                acquiredAt = now
                            } else if (!needLock && wl.isHeld) {
                                wl.release()
                            }
                        }
                        delay(INTERVAL_MS)
                        val after = SystemClock.elapsedRealtime()
                        if (!starved && after - last > STARVATION_MS) {
                            Log.w(TAG, "heartbeat starvation: ${after - last} ms (CPU-Suspend) → WakeLock ab jetzt bei Display aus")
                            starved = true
                        }
                        last = after
                    }
                } finally {
                    // Release NACH dem letzten acquire dieses Loops — ein release aus
                    // stop() könnte sonst von einem späten acquire überholt werden.
                    synchronized(lock) {
                        if (heartbeatJob === self || heartbeatJob == null) wakeLock?.release()
                        if (heartbeatJob === self) heartbeatJob = null
                    }
                }
            }
            heartbeatJob = job
            job.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            initBurstJob?.cancel()
            initBurstJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            // Die WakeLock gibt das finally des Loops frei. Lief gar kein Loop,
            // ist sie ohnehin nicht gehalten.
        }
    }

    /**
     * Beim Abriss, VOR [stop] aufrufen: lag der letzte Heartbeat länger als
     * [STARVATION_MS] zurück, hat der CPU-Suspend den Heartbeat ausgehungert —
     * genau der Fall, den der Loop selbst nicht mehr messen kann, weil er vor
     * dem Ende seines delay() gecancelt wird.
     */
    fun noteStarvationAtDisconnect() {
        val running = synchronized(lock) { heartbeatJob?.isActive == true }
        if (!running || starved) return
        val gap = SystemClock.elapsedRealtime() - lastTickElapsed
        if (gap > STARVATION_MS) {
            Log.w(TAG, "heartbeat starvation beim Abriss erkannt: letzter Heartbeat vor $gap ms → WakeLock ab jetzt bei Display aus")
            starved = true
        }
    }

    private companion object {
        const val TAG = "BleRepository"
        const val INTERVAL_MS = 600L
        const val STARVATION_MS = 2_000L
        const val WAKE_TIMEOUT_MS = 60 * 60_000L
        const val WAKE_RENEW_MARGIN_MS = 5 * 60_000L
    }
}
