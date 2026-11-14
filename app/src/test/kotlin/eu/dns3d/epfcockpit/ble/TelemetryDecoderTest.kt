package eu.dns3d.epfcockpit.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-Layout des Telemetrie-Frames (siehe docs/PROTOCOL.md). Nagelt vor allem
 * die Skalierungen fest — der Strom ist s16/64 (Hobbywing-Festkomma), NICHT
 * 0,01 A/LSB; mit der falschen Skala zeigte die App 36 % zu wenig Ampere.
 */
class TelemetryDecoderTest {

    /** 25-Byte-Frame: Header, Typ 1, Nutzdaten, Statuswort [21..22], CRC [23..24]. */
    private fun frame(
        gear: Int = 3,
        battery: Int = 87,
        speedRaw: Int = 25_500,       // 25,5 km/h
        speedRaw2: Int = speedRaw,
        voltageRaw: Int = 523,        // 52,3 V
        currentRaw: Int = 640,        // 10 A
        escTemp: Int = 41,
        motorTemp: Int = 38,
        tripRaw: Int = 123,           // 12,3 km
        totalRaw: Int = 70_000,       // 7000,0 km (braucht das dritte Byte)
        statusWord: Int = 0x0002,
        type: Int = 0x01,
        size: Int = 25,
    ): ByteArray {
        val b = ByteArray(25)
        b[0] = BleConstants.FRAME_HEADER_EPF
        b[2] = 0x19
        b[3] = type.toByte()
        b[4] = gear.toByte()
        b[5] = battery.toByte()
        fun put16(i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
        put16(6, speedRaw)
        put16(8, speedRaw2)
        put16(10, voltageRaw)
        put16(12, currentRaw)
        b[14] = escTemp.toByte()
        b[15] = motorTemp.toByte()
        put16(16, tripRaw)
        b[18] = (totalRaw shr 16).toByte(); b[19] = (totalRaw shr 8).toByte(); b[20] = totalRaw.toByte()
        put16(21, statusWord)
        return b.copyOf(size)
    }

    @Test
    fun `dekodiert alle Felder mit den verifizierten Skalierungen`() {
        val t = TelemetryDecoder.decode(frame())!!
        assertEquals(3, t.gear)
        assertEquals(87, t.batteryPercent)
        assertEquals(25.5f, t.speedKmh, 0.001f)
        assertEquals(52.3f, t.voltage, 0.001f)
        assertEquals(10f, t.currentAmps, 0.0001f)
        assertEquals(41, t.controllerTempC)
        assertEquals(38, t.motorTempC)
        assertEquals(12.3f, t.tripKm, 0.001f)
        assertEquals(7000f, t.totalKm, 0.01f)
        assertEquals(523f, t.watts, 0.1f)
    }

    @Test
    fun `Strom ist vorzeichenbehaftet und durch 64 skaliert`() {
        val regen = TelemetryDecoder.decode(frame(currentRaw = 0x10000 - 320))!!
        assertEquals(-5f, regen.currentAmps, 0.0001f)
        assertTrue(regen.isRegenerating)
        assertEquals(regen.voltage * 5f, regen.absWatts, 0.1f)
        // 1 LSB = 1/64 A, nicht 0,01 A.
        assertEquals(1f / 64f, TelemetryDecoder.decode(frame(currentRaw = 1))!!.currentAmps, 1e-6f)
    }

    @Test
    fun `Temperaturen sind vorzeichenbehaftet`() {
        val t = TelemetryDecoder.decode(frame(escTemp = -5, motorTemp = -12))!!
        assertEquals(-5, t.controllerTempC)
        assertEquals(-12, t.motorTempC)
    }

    @Test
    fun `Geschwindigkeit ist das Maximum beider Kopien`() {
        assertEquals(12f, TelemetryDecoder.decode(frame(speedRaw = 0, speedRaw2 = 12_000))!!.speedKmh, 0.001f)
        assertEquals(12f, TelemetryDecoder.decode(frame(speedRaw = 12_000, speedRaw2 = 0))!!.speedKmh, 0.001f)
    }

    @Test
    fun `Statusbits - Bit 5 Parken oder Stand-Mode, Bit 2 Licht`() {
        val unlocked = TelemetryDecoder.decode(frame(statusWord = 0x0002))!!
        assertFalse(unlocked.parking)
        assertFalse(unlocked.headlightOn)
        val parked = TelemetryDecoder.decode(frame(statusWord = 0x0022))!!
        assertTrue(parked.parking)
        val light = TelemetryDecoder.decode(frame(statusWord = 0x0106))!!
        assertTrue(light.headlightOn)
        assertEquals(0x06, light.statusByte)
        assertEquals(0x0106, light.statusWord)
    }

    @Test
    fun `Akku wird auf 0 bis 100 begrenzt`() {
        assertEquals(100, TelemetryDecoder.decode(frame(battery = 130))!!.batteryPercent)
    }

    @Test
    fun `Echo-Frames, falsche Header und zu kurze Frames werden verworfen`() {
        assertNull(TelemetryDecoder.decode(frame(type = 0x00)))
        assertNull(TelemetryDecoder.decode(frame().also { it[0] = 0x55 }))
        assertNull(TelemetryDecoder.decode(frame(size = 21)))
    }

    @Test
    fun `kurzes Frame ohne Statusbyte liefert Status 0`() {
        val t = TelemetryDecoder.decode(frame(statusWord = 0x0022, size = 22))!!
        assertEquals(0, t.statusByte)
        assertFalse(t.parking)
    }

    @Test
    fun `gleiche Messwerte sind gleich - StateFlow fasst identische Frames zusammen`() {
        assertEquals(TelemetryDecoder.decode(frame()), TelemetryDecoder.decode(frame()))
    }
}
