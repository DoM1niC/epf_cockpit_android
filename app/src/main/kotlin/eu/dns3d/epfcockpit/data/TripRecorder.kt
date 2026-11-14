package eu.dns3d.epfcockpit.data

import eu.dns3d.epfcockpit.ble.Telemetry
import kotlin.math.max

/**
 * Verdichtet Telemetrie-Samples zu einem [Trip].
 *
 * Eine Fahrt beginnt mit dem ersten Sample in Bewegung und endet mit dem
 * letzten — NICHT mit Verbindungsauf- und -abbau. Wer den Roller eine Stunde
 * verbunden in der Garage stehen lässt, bekommt weder eine Stunde Dauer noch
 * einen gegen 0 gedrückten Schnitt.
 *
 *  - Distanz: Delta des Roller-eigenen Trip-Zählers (Radumfang ist dort schon
 *    drin). Ein Zurücksetzen des Zählers mitten in der Fahrt wird überbrückt.
 *  - Ø-Geschwindigkeit: zeitgewichtet über die Bewegungszeit.
 *  - Energie: |U·I|·dt, ab Fahrtbeginn bis kurz nach dem letzten Rollen.
 *  - Lücken (Funkabriss, identische und daher zusammengefasste Frames) zählen
 *    weder als Bewegungszeit noch als Energie.
 */
class TripRecorder(private val scooterAddress: String) {

    private var lastSampleAt: Long = 0L
    private var startedAt: Long = 0L
    private var lastMovingAt: Long = 0L

    private var baseTripKm: Float = Float.NaN
    private var lastTripKm: Float = Float.NaN
    private var carriedKm: Double = 0.0
    private var distanceAtLastMoveKm: Double = 0.0

    private var startBattery: Int = -1
    private var endBattery: Int = -1
    private var maxSpeed: Float = 0f
    private var speedTimeSum: Double = 0.0
    private var movingMs: Long = 0L
    private var energyWh: Double = 0.0

    /** True solange noch kein Sample reingekommen ist (initial state). */
    fun isEmpty(): Boolean = lastSampleAt == 0L

    /** True sobald der Roller in dieser Aufzeichnung einmal gerollt ist. */
    val hasStarted: Boolean get() = startedAt != 0L

    /** Zeitpunkt des Losfahrens (ms) oder 0, solange [hasStarted] false ist. */
    val startedAtMillis: Long get() = startedAt

    fun onSample(sample: Telemetry, nowMillis: Long) {
        val gap = if (lastSampleAt != 0L) nowMillis - lastSampleAt else 0L
        val dt = if (gap in 1..MAX_SAMPLE_GAP_MS) gap else 0L
        lastSampleAt = nowMillis

        // Odometer: Basis beim ersten Sample; springt der Zähler zurück
        // (Tageskilometer-Reset am Roller), die bisherige Strecke mitnehmen.
        if (lastTripKm.isNaN()) {
            baseTripKm = sample.tripKm
        } else if (sample.tripKm < lastTripKm - COUNTER_RESET_EPS_KM) {
            carriedKm += (lastTripKm - baseTripKm).coerceAtLeast(0f)
            baseTripKm = sample.tripKm
        }
        lastTripKm = sample.tripKm

        val moving = sample.speedKmh >= MOVING_SPEED_KMH
        if (moving) {
            if (startedAt == 0L) {
                startedAt = nowMillis
                startBattery = sample.batteryPercent
                // Strecke vor dem Losfahren (Schieben) gehört nicht zur Fahrt.
                carriedKm = 0.0
                baseTripKm = sample.tripKm
            }
            lastMovingAt = nowMillis
            maxSpeed = max(maxSpeed, sample.speedKmh)
            speedTimeSum += sample.speedKmh.toDouble() * dt
            movingMs += dt
        }
        if (startedAt == 0L) return

        // "In der Fahrt" = rollt oder hat eben noch gerollt (Ampel). Danach
        // (abgestellt, evtl. am Ladegerät) wird nichts mehr dazugezählt.
        if (moving || nowMillis - lastMovingAt <= STOP_TAIL_MS) {
            energyWh += sample.absWatts * (dt / 3_600_000.0)
            endBattery = sample.batteryPercent
            // Der 0,1-km-Zähler kann kurz nach dem Anhalten noch umspringen.
            distanceAtLastMoveKm = carriedKm + (lastTripKm - baseTripKm).coerceAtLeast(0f)
        }
    }

    /**
     * Schnappschuss der aggregierten Werte. Null, wenn der Roller nie gerollt
     * ist. Der "≥10 m"-Filter sitzt im Coordinator.
     */
    fun snapshot(): Trip? {
        if (startedAt == 0L) return null
        return Trip(
            startedAt = startedAt,
            endedAt = max(lastMovingAt, startedAt),
            distanceKm = distanceAtLastMoveKm.toFloat(),
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = if (movingMs > 0) (speedTimeSum / movingMs).toFloat() else maxSpeed,
            energyWh = energyWh.toFloat(),
            startBatteryPct = startBattery,
            endBatteryPct = endBattery,
            scooterAddress = scooterAddress,
        )
    }

    companion object {
        /** Ab dieser Geschwindigkeit gilt der Roller als rollend. */
        const val MOVING_SPEED_KMH = 1.0f
        /** Größere Abstände zwischen zwei Samples sind Lücken, keine Fahrzeit. */
        const val MAX_SAMPLE_GAP_MS = 5_000L
        /** So lange nach dem letzten Rollen zählen Energie/Akku/Strecke noch mit. */
        const val STOP_TAIL_MS = 60_000L
        private const val COUNTER_RESET_EPS_KM = 0.05f
    }
}
