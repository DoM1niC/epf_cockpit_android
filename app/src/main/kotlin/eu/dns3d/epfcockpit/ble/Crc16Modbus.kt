package eu.dns3d.epfcockpit.ble

/**
 * MODBUS CRC-16 (polynomial 0xA001, init 0xFFFF, refin/refout true, xorout 0x0000).
 *
 * The Zydtech frames append the CRC in **big-endian** (high byte first), which is
 * the opposite of the standard MODBUS RTU wire order. See ZydDash BLE_Telemetry.md.
 */
object Crc16Modbus {

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001 else (crc ushr 1)
            }
        }
        return crc and 0xFFFF
    }

    /** Append CRC as big-endian (high byte, then low byte). */
    fun append(frame: ByteArray): ByteArray {
        val crc = compute(frame)
        return frame + byteArrayOf(((crc ushr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
    }

    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 3) return false
        val payloadEnd = frame.size - 2
        val expected = compute(frame, 0, payloadEnd)
        val actual = ((frame[payloadEnd].toInt() and 0xFF) shl 8) or
                (frame[payloadEnd + 1].toInt() and 0xFF)
        return expected == actual
    }
}
