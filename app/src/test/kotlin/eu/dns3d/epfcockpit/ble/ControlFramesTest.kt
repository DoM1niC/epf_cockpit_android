package eu.dns3d.epfcockpit.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Statusframes gegen die sniff-verifizierten Bytes der offiziellen App (siehe
 * docs/PROTOCOL.md). Die Licht-/Reset-Bits dürfen die bekannten Frames nicht
 * verändern, solange sie aus sind.
 */
class ControlFramesTest {

    private fun hex(b: ByteArray) = b.toHex()

    @Test
    fun `sniff-verifizierte Park- und Mode-Frames bleiben byte-identisch`() {
        assertEquals("AF 00 0A 22 00 06 0F 14 17 60", hex(ControlFrames.pulseState(parked = true, stand = true)))
        assertEquals("AF 00 0A 22 00 06 0F 14 17 60", hex(ControlFrames.pulseState(parked = true, stand = false)))
        assertEquals("AF 00 0A 82 00 06 0F 14 97 79", hex(ControlFrames.pulseState(parked = false, stand = false)))
        assertEquals("AF 00 0A A2 00 06 0F 14 16 BE", hex(ControlFrames.pulseState(parked = false, stand = true)))
        assertEquals(hex(ControlFrames.modeKickStart()), hex(ControlFrames.parkingOff()))
    }

    @Test
    fun `Licht setzt Bit 2, Trip-Reset Bit 3`() {
        val light = ControlFrames.pulseState(parked = false, stand = false, light = true)
        assertEquals(0x86, light[3].toInt() and 0xFF)
        val reset = ControlFrames.pulseState(parked = true, stand = false, resetTrip = true)
        assertEquals(0x2A, reset[3].toInt() and 0xFF)
        val both = ControlFrames.pulseState(parked = false, stand = true, light = true, resetTrip = true)
        assertEquals(0xAE, both[3].toInt() and 0xFF)
    }

    @Test
    fun `geparkt wird das Licht-Bit nie gesendet`() {
        // Live verifiziert: der Controller ignoriert Bit 2 im Park-Zustand und
        // schaltet das Licht beim Parken selbst ab → Frame bleibt das gesniffte 0x22.
        assertEquals(
            "AF 00 0A 22 00 06 0F 14 17 60",
            hex(ControlFrames.pulseState(parked = true, stand = true, light = true)),
        )
    }

    @Test
    fun `g3 landet in Byte 7`() {
        val f = ControlFrames.pulseState(parked = true, stand = false, g3MaxKmh = 22)
        assertEquals(22, f[7].toInt() and 0xFF)
        assertEquals(10, f.size)
    }
}
