package eu.dns3d.epfcockpit.data

import eu.dns3d.epfcockpit.ble.Telemetry
import kotlin.math.max

/**
 * Accumulates telemetry samples into a [Trip] aggregate while the user is riding.
 *
 * Distance comes from delta of the scooter's own trip-counter (which already includes
 * wheel circumference / no-slip correction). Energy is integrated as |U·I| * dt.
 */
class TripRecorder(private val scooterAddress: String) {

    private var startedAt: Long = 0L
    private var lastSampleAt: Long = 0L
    private var startTripKm: Float = Float.NaN
    private var endTripKm: Float = Float.NaN
    private var startBattery: Int = -1
    private var endBattery: Int = -1
    private var maxSpeed: Float = 0f
    private var speedSum: Double = 0.0
    private var speedSamples: Int = 0
    private var energyWh: Double = 0.0

    fun onSample(sample: Telemetry, nowMillis: Long) {
        if (startedAt == 0L) {
            startedAt = nowMillis
            startTripKm = sample.tripKm
            startBattery = sample.batteryPercent
        }
        if (lastSampleAt != 0L) {
            val dtH = (nowMillis - lastSampleAt) / 3_600_000.0
            energyWh += sample.absWatts * dtH
        }
        lastSampleAt = nowMillis
        endTripKm = sample.tripKm
        endBattery = sample.batteryPercent
        maxSpeed = max(maxSpeed, sample.speedKmh)
        speedSum += sample.speedKmh
        speedSamples += 1
    }

    /** Returns the trip if it covered any distance, otherwise null. */
    fun finalize(): Trip? {
        if (startedAt == 0L) return null
        val distance = (endTripKm - startTripKm).coerceAtLeast(0f)
        if (distance < 0.01f) return null
        return Trip(
            startedAt = startedAt,
            endedAt = lastSampleAt,
            distanceKm = distance,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = if (speedSamples > 0) (speedSum / speedSamples).toFloat() else 0f,
            energyWh = energyWh.toFloat(),
            startBatteryPct = startBattery,
            endBatteryPct = endBattery,
            scooterAddress = scooterAddress,
        )
    }
}
