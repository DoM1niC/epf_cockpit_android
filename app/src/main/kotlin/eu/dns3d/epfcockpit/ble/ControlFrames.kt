package eu.dns3d.epfcockpit.ble

/**
 * Builders for the control frames written to the low-level TX char.
 *
 * Example reference frame (ZydDash): `af 00 0a 82 03 05 0f 14 67 3d`
 *   [0]   header 0xAF
 *   [1]   reserved
 *   [2]   length 0x0A
 *   [3]   flags byte:
 *           bit7 = lock,  bit3 = trip reset,  bit2 = light,  bit1-0 = gear (1..3)
 *   [4]   reserved
 *   [5]   max km/h for gear 1
 *   [6]   max km/h for gear 2
 *   [7]   max km/h for gear 3
 *   [8-9] CRC16-MODBUS (big-endian)
 */
data class ControlState(
    val locked: Boolean = false,
    val lightOn: Boolean = false,
    val gear: Int = 2,
    val gear1MaxKmh: Int = 6,
    val gear2MaxKmh: Int = 15,
    val gear3MaxKmh: Int = 20,
    val resetTrip: Boolean = false,
) {
    init {
        require(gear in 1..3) { "gear must be 1..3" }
    }
}

object ControlFrames {

    fun build(state: ControlState): ByteArray {
        var flags = (state.gear and 0x03)
        if (state.lightOn)   flags = flags or 0x04
        if (state.resetTrip) flags = flags or 0x08
        if (state.locked)    flags = flags or 0x80

        val payload = byteArrayOf(
            BleConstants.FRAME_HEADER_EPF,
            0x00,
            0x0A,
            flags.toByte(),
            0x00,
            state.gear1MaxKmh.toByte(),
            state.gear2MaxKmh.toByte(),
            state.gear3MaxKmh.toByte(),
        )
        return Crc16Modbus.append(payload)
    }
}

/**
 * AT-style commands sent over the high-level TX char.
 *
 *   AT+PWD[123456]   → verify PIN, scooter replies "OK+PWD:Y" or "OK+PWD:N"
 *   AT+PWDM[123456]  → set a new PIN
 *   AT+NAME[xxxx]    → rename the device
 */
object AtCommands {
    fun verifyPin(pin: String): ByteArray  = "AT+PWD[$pin]".toByteArray(Charsets.US_ASCII)
    fun changePin(newPin: String): ByteArray = "AT+PWDM[$newPin]".toByteArray(Charsets.US_ASCII)
    fun rename(name: String): ByteArray    = "AT+NAME[$name]".toByteArray(Charsets.US_ASCII)

    fun parseResponse(bytes: ByteArray): AtResponse {
        val text = bytes.toString(Charsets.US_ASCII).trim()
        return when {
            text.startsWith("OK+PWD:Y", ignoreCase = true) -> AtResponse.PinOk
            text.startsWith("OK+PWD:N", ignoreCase = true) -> AtResponse.PinRejected
            text.startsWith("OK+PWDM", ignoreCase = true)  -> AtResponse.PinChanged
            text.startsWith("OK", ignoreCase = true)       -> AtResponse.Ok(text)
            else -> AtResponse.Unknown(text)
        }
    }
}

sealed interface AtResponse {
    data object PinOk : AtResponse
    data object PinRejected : AtResponse
    data object PinChanged : AtResponse
    data class Ok(val text: String) : AtResponse
    data class Unknown(val text: String) : AtResponse
}
