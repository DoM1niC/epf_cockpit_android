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

    /**
     * Append CRC as big-endian (high byte, then low byte) — für ePF-1 / Zydtech
     * (ZydDash convention).
     */
    fun append(frame: ByteArray): ByteArray {
        val crc = compute(frame)
        return frame + byteArrayOf(((crc ushr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
    }

    /**
     * Append CRC as little-endian (low byte, then high byte) — für EPF Pulse Plus
     * / Hobbywing. Sniff-verifiziert: die original EPF-App schickt CRC LE auf wire,
     * der Hobbywing-Controller verwirft Frames mit BE-CRC stumm.
     */
    fun appendLE(frame: ByteArray): ByteArray {
        val crc = compute(frame)
        return frame + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    /**
     * Accepts both CRC byte orders:
     *   - ePF-1 / Zydtech: big-endian (high byte first), per ZydDash docs
     *   - EPF Pulse Plus / Hobbywing: little-endian (standard MODBUS RTU),
     *     confirmed via HCI snoop of com.zydtech.epowerfun (e.g. payload
     *     ending `0x14 96 A7` → CRC = 0xA796 in BE = 0x96A7 in LE).
     * Trying both keeps us compatible with both firmware lineages.
     */
    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 3) return false
        val payloadEnd = frame.size - 2
        val expected = compute(frame, 0, payloadEnd)
        val hi = frame[payloadEnd].toInt() and 0xFF
        val lo = frame[payloadEnd + 1].toInt() and 0xFF
        val be = (hi shl 8) or lo
        val le = (lo shl 8) or hi
        return expected == be || expected == le
    }
}
