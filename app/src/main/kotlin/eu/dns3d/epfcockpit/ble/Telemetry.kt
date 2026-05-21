package eu.dns3d.epfcockpit.ble

/**
 * Decoded snapshot of a telemetry frame (type 2) emitted on the low-level RX char.
 *
 * Byte mapping per ZydDash (ePF-1):
 *  [4]      gear (1..3)
 *  [5]      battery %
 *  [6..7]   speed   u16 * 0.001 km/h
 *  [10..11] voltage u16 * 0.1   V
 *  [12..13] current i16 * 0.01  A (signed — regen produces negative)
 *  [14]     controller temperature °C
 *  [16..17] trip km   u16 * 0.1
 *  [18..20] total km  u24 * 0.1
 *
 * Watts are derived client-side as |U * I|.
 */
data class Telemetry(
    val gear: Int,
    val batteryPercent: Int,
    val speedKmh: Float,
    val voltage: Float,
    val currentAmps: Float,
    val controllerTempC: Int,
    val tripKm: Float,
    val totalKm: Float,
    val rawHex: String,
) {
    val watts: Float get() = voltage * currentAmps
    val absWatts: Float get() = kotlin.math.abs(watts)
    val isRegenerating: Boolean get() = currentAmps < -0.05f
}

object TelemetryDecoder {

    /** Returns null if [frame] is not a recognisable telemetry frame. */
    fun decode(frame: ByteArray): Telemetry? {
        if (frame.size < 22) return null
        if (frame[0] != BleConstants.FRAME_HEADER_EPF &&
            frame[0] != BleConstants.FRAME_HEADER_ALT) return null
        if (!Crc16Modbus.verify(frame)) return null

        fun u8(i: Int)  = frame[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun s16(i: Int): Int {
            val v = u16(i)
            return if (v and 0x8000 != 0) v - 0x10000 else v
        }
        fun u24(i: Int) = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

        return Telemetry(
            gear = u8(4),
            batteryPercent = u8(5).coerceIn(0, 100),
            speedKmh = u16(6) * 0.001f,
            voltage = u16(10) * 0.1f,
            currentAmps = s16(12) * 0.01f,
            controllerTempC = u8(14),
            tripKm = u16(16) * 0.1f,
            totalKm = u24(18) * 0.1f,
            rawHex = frame.toHex(),
        )
    }
}

fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = replace(Regex("[^0-9A-Fa-f]"), "")
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
