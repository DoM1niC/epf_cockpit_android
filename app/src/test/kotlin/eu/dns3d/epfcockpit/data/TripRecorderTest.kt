package eu.dns3d.epfcockpit.data

import eu.dns3d.epfcockpit.ble.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    private fun sample(speed: Float, tripKm: Float, battery: Int = 80, volts: Float = 50f, amps: Float = 0f) =
        Telemetry(
            gear = 3, batteryPercent = battery, speedKmh = speed, voltage = volts, currentAmps = amps,
            controllerTempC = 30, motorTempC = 30, tripKm = tripKm, totalKm = 100f, statusByte = 0x02,
        )

    private val t0 = 1_700_000_000_000L

    @Test
    fun `ohne Bewegung entsteht keine Fahrt`() {
        val r = TripRecorder("AA")
        assertTrue(r.isEmpty())
        r.onSample(sample(0f, 5f), t0)
        r.onSample(sample(0.4f, 5f), t0 + 1000)
        assertFalse(r.isEmpty())
        assertFalse(r.hasStarted)
        assertNull(r.snapshot())
    }

    @Test
    fun `Fahrt beginnt beim Losrollen und endet beim letzten Rollen`() {
        val r = TripRecorder("AA")
        // 10 min verbunden im Stand
        r.onSample(sample(0f, 5f, battery = 90), t0)
        r.onSample(sample(0f, 5f, battery = 90), t0 + 600_000)
        // 100 s Fahrt mit 20 km/h, 1-s-Takt
        for (i in 0..100) r.onSample(sample(20f, 5f + i * 0.005f, battery = 88), t0 + 600_000 + i * 1000L)
        // danach 30 min abgestellt
        r.onSample(sample(0f, 5.5f, battery = 88), t0 + 701_000)
        r.onSample(sample(0f, 5.5f, battery = 95), t0 + 2_500_000)

        val trip = r.snapshot()!!
        assertEquals(t0 + 600_000, trip.startedAt)
        assertEquals(t0 + 700_000, trip.endedAt)
        assertEquals(0.5f, trip.distanceKm, 0.01f)
        assertEquals(20f, trip.avgSpeedKmh, 0.01f)
        assertEquals(20f, trip.maxSpeedKmh, 0.01f)
        assertEquals(88, trip.startBatteryPct)
        // Das Laden nach dem Abstellen (95 %) gehört nicht mehr zur Fahrt.
        assertEquals(88, trip.endBatteryPct)
        assertEquals("AA", trip.scooterAddress)
    }

    @Test
    fun `Standzeit an der Ampel drueckt den Schnitt nicht`() {
        val r = TripRecorder("AA")
        for (i in 0..10) r.onSample(sample(20f, 1f), t0 + i * 1000L)
        for (i in 11..40) r.onSample(sample(0f, 1f), t0 + i * 1000L)   // 30 s Ampel
        for (i in 41..50) r.onSample(sample(10f, 1.1f), t0 + i * 1000L)
        val trip = r.snapshot()!!
        // 10 s à 20 km/h + 10 s à 10 km/h Bewegungszeit
        assertEquals(15f, trip.avgSpeedKmh, 0.01f)
        assertEquals(t0 + 50_000, trip.endedAt)
    }

    @Test
    fun `Energie wird ueber die Zeit integriert, Luecken zaehlen nicht`() {
        val r = TripRecorder("AA")
        // 500 W für 36 s = 5 Wh
        for (i in 0..36) r.onSample(sample(20f, 1f, volts = 50f, amps = 10f), t0 + i * 1000L)
        assertEquals(5f, r.snapshot()!!.energyWh, 0.01f)
        // 2 min Funkloch, danach weiter: die Lücke darf nicht mit 500 W verbucht werden.
        r.onSample(sample(20f, 1.5f, volts = 50f, amps = 10f), t0 + 156_000)
        assertEquals(5f, r.snapshot()!!.energyWh, 0.01f)
        assertEquals(20f, r.snapshot()!!.avgSpeedKmh, 0.01f)
        // Rekuperation zählt als Betrag.
        r.onSample(sample(20f, 1.5f, volts = 50f, amps = -10f), t0 + 157_000)
        assertTrue(r.snapshot()!!.energyWh > 5.1f)
    }

    @Test
    fun `Trip-Reset am Roller mitten in der Fahrt verliert keine Strecke`() {
        val r = TripRecorder("AA")
        r.onSample(sample(15f, 10.0f), t0)
        r.onSample(sample(15f, 10.4f), t0 + 1000)
        r.onSample(sample(15f, 0.0f), t0 + 2000)   // Zähler zurückgesetzt
        r.onSample(sample(15f, 0.3f), t0 + 3000)
        assertEquals(0.7f, r.snapshot()!!.distanceKm, 0.001f)
    }

    @Test
    fun `Strecke vor dem Losfahren gehoert nicht zur Fahrt`() {
        val r = TripRecorder("AA")
        r.onSample(sample(0f, 3.0f), t0)
        r.onSample(sample(0.5f, 3.1f), t0 + 1000)  // geschoben
        r.onSample(sample(12f, 3.1f), t0 + 2000)
        r.onSample(sample(12f, 3.3f), t0 + 3000)
        assertEquals(0.2f, r.snapshot()!!.distanceKm, 0.001f)
    }
}
