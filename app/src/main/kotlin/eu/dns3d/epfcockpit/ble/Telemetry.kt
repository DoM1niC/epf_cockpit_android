package eu.dns3d.epfcockpit.ble

/**
 * Decoded snapshot of a telemetry frame (type 1) emitted on the low-level RX char.
 *
 * Byte mapping verified against the decompiled official app
 * (com.zydtech.epowerfun, BleCore.parsingDataBuf) on a Pulse Plus:
 *  [4]      gear (1..3)
 *  [5]      battery %
 *  [6..7]   speed   u16 * 0.001 km/h ([8..9] carries a redundant copy;
 *           the official app displays max of both)
 *  [10..11] voltage u16 * 0.1   V
 *  [12..13] current i16 / 64    A (signed — regen produces negative;
 *           Hobbywing fixed-point. NOT the 0.01 A/LSB that ZydDash
 *           documents for the ePF-1 — that scale reads ~36 % too low here)
 *  [14]     controller/ESC temperature °C (signed)
 *  [15]     motor temperature °C (signed)
 *  [16..17] trip km   u16 * 0.1
 *  [18..20] total km  u24 * 0.1
 *
 * Watts are derived client-side as |U * I| (the official app does the same).
 */
data class Telemetry(
    val gear: Int,
    val batteryPercent: Int,
    val speedKmh: Float,
    val voltage: Float,
    val currentAmps: Float,
    val controllerTempC: Int,
    val motorTempC: Int,
    val tripKm: Float,
    val totalKm: Float,
    /**
     * Status byte from offset [22]. Bit 5 (0x20) = parking lock engaged.
     * Live-verified on Pulse Plus (HW_EF043840):
     *   0x02 → unlocked / active
     *   0x22 → parking lock engaged
     * (Bit 7 was a red herring — appeared briefly during light-toggle
     *  experiments but isn't the park indicator.)
     */
    val statusByte: Int,
    /**
     * Komplettes Status-Bitfeld [21..22] als u16 BE (Zydtech „registerZero").
     * Low-Byte == [statusByte]. Nur für Logging/Diagnose.
     */
    val statusWord: Int = statusByte,
    val rawHex: String,
) {
    val watts: Float get() = voltage * currentAmps
    val absWatts: Float get() = kotlin.math.abs(watts)
    val isRegenerating: Boolean get() = currentAmps < -0.05f
    val parking: Boolean get() = (statusByte and 0x20) != 0
    /** u16-Bit 2 = `headLightSw` laut dekompilierter offizieller App. */
    val headlightOn: Boolean get() = (statusByte and 0x04) != 0
}

object TelemetryDecoder {

    private const val TAG = "TelemetryDecoder"

    /** Returns null if [frame] is not a recognisable telemetry frame. */
    fun decode(frame: ByteArray): Telemetry? {
        if (frame.size < 22) {
            android.util.Log.d(TAG, "skip: size=${frame.size} < 22 hex=${frame.toHex()}")
            return null
        }
        if (frame[0] != BleConstants.FRAME_HEADER_EPF &&
            frame[0] != BleConstants.FRAME_HEADER_ALT) {
            android.util.Log.d(TAG, "skip: bad header=0x%02X".format(frame[0]))
            return null
        }
        // Frame type 1 is the telemetry payload (per HCI sniff:
        //   AF 00 19 01 ...). Type 0 is the "echo" of the last command and
        //   carries no live values — decoding it as telemetry overwrites the
        //   real values with zeros.
        val isTelemetryType = frame.size >= 4 && frame[3].toInt() and 0xFF == 0x01
        if (!isTelemetryType) {
            android.util.Log.v(TAG, "skip: not type 1 (echo frame) hex=${frame.toHex()}")
            return null
        }
        if (!Crc16Modbus.verify(frame)) {
            // Be permissive: log but decode anyway. Pulse Plus may use a
            // non-MODBUS checksum we haven't characterised yet.
            android.util.Log.w(TAG, "CRC mismatch (decoding anyway) hex=${frame.toHex()}")
        }

        fun u8(i: Int)  = frame[i].toInt() and 0xFF
        fun s8(i: Int)  = frame[i].toInt()
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun s16(i: Int): Int {
            val v = u16(i)
            return if (v and 0x8000 != 0) v - 0x10000 else v
        }
        fun u24(i: Int) = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

        return Telemetry(
            gear = u8(4),
            batteryPercent = u8(5).coerceIn(0, 100),
            speedKmh = maxOf(u16(6), u16(8)) * 0.001f,
            voltage = u16(10) * 0.1f,
            currentAmps = s16(12) / 64f,
            controllerTempC = s8(14),
            motorTempC = s8(15),
            tripKm = u16(16) * 0.1f,
            totalKm = u24(18) * 0.1f,
            // 25-byte telemetry frames include a status byte at [22] before
            // the CRC; shorter frames don't, fall back to 0.
            statusByte = if (frame.size >= 25) u8(22) else 0,
            statusWord = if (frame.size >= 25) u16(21) else 0,
            rawHex = frame.toHex(),
        ).also { t ->
            android.util.Log.v(TAG, "telemetry: status=0x%02X parking=%b gear=%d batt=%d%% v=%.1f"
                .format(t.statusByte, t.parking, t.gear, t.batteryPercent, t.voltage))
        }
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
