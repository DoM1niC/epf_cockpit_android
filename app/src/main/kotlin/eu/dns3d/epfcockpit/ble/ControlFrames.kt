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

    // === Heartbeat & init ===

    /**
     * Real Pulse-Plus heartbeat (~600 ms cadence in the official app).
     *   `A5 02 FD 5A` — 4 bytes, fire-and-forget over low TX.
     * The controller streams telemetry only while it's being kicked with
     * this every ~600 ms; without it, the GATT connection is torn down
     * after ~30 s with `GATT CONN TERMINATE PEER USER`.
     */
    val PULSE_HEARTBEAT: ByteArray = byteArrayOf(
        0xA5.toByte(), 0x02, 0xFD.toByte(), 0x5A.toByte(),
    )

    /**
     * Wake-up / init burst observed 10x right after `AT+PWD[…]` returns OK.
     * Sent 5x with a tiny delay between them in the official app's flow.
     */
    val PULSE_INIT: ByteArray = byteArrayOf(
        0xA5.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x5A.toByte(),
    )

    // === Discrete control frames captured from the official app ===
    //
    // Pulse Plus uses an OP-byte encoding (high nibble = action, low nibble =
    // bit-flag on/off). All carry the canonical suffix `… 06 0F 14 <CRC_LE>`.
    //
    // Frames captured in sniff #2 — confirmed live on a Pulse Plus:
    //   OP 0x82 → Parking OFF / Unlock (scooter active)
    //   OP 0x22 → Parking ON  / Lock   (scooter parked)
    //
    // Mode (Stand ↔ Kickstart) frames were NOT captured in sniff #2 — the
    // two AF writes both happened to be Parking toggles. Needs another sniff
    // with only the Mode buttons being pressed.

    /** Parking OFF — unlock the scooter from P-mode. */
    val PARKING_OFF: ByteArray = byteArrayOf(
        0xAF.toByte(), 0x00, 0x0A, 0x82.toByte(), 0x00,
        0x06, 0x0F, 0x14, 0x97.toByte(), 0x79,
    )

    /** Parking ON — engage the park lock. */
    val PARKING_ON: ByteArray = byteArrayOf(
        0xAF.toByte(), 0x00, 0x0A, 0x22, 0x00,
        0x06, 0x0F, 0x14, 0x17, 0x60,
    )

    /**
     * Ride mode "Stand Stop" — scooter parks and waits in stand mode
     * (must be physically pushed to start). Sniff-verified bit pattern:
     * Bit 7 (mode) + Bit 5 (park) + Bit 1.
     */
    val MODE_STAND_STOP: ByteArray = byteArrayOf(
        0xAF.toByte(), 0x00, 0x0A, 0xA2.toByte(), 0x00,
        0x06, 0x0F, 0x14, 0x16, 0xBE.toByte(),
    )

    /**
     * Ride mode "Kick Start" — scooter unparks and starts in kickstart mode
     * (goes on throttle alone after a brief push). Byte-identical to
     * [PARKING_OFF] — the official app reuses the same frame for both
     * "Park OFF" and "Kick Start" actions, which simultaneously unparks the
     * scooter and switches the ride mode.
     */
    val MODE_KICK_START: ByteArray = PARKING_OFF

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
    fun queryDevice(): ByteArray           = "AT+DEVICE?".toByteArray(Charsets.US_ASCII)
    fun queryVoiceOff(): ByteArray         = "AT+TLVOICEOFF?".toByteArray(Charsets.US_ASCII)
    /** Voice-prompt aka "Blinkerton": on=1, off=0. */
    fun setVoiceOff(on: Boolean): ByteArray =
        "AT+TLVOICEOFF[${if (on) 1 else 0}]".toByteArray(Charsets.US_ASCII)

    fun parseResponse(bytes: ByteArray): AtResponse {
        val text = bytes.toString(Charsets.US_ASCII).trim()
        return when {
            text.startsWith("OK+PWD:Y", ignoreCase = true) -> AtResponse.PinOk
            text.startsWith("OK+PWD:N", ignoreCase = true) -> AtResponse.PinRejected
            text.startsWith("OK+PWDM", ignoreCase = true)  -> AtResponse.PinChanged
            text.startsWith("OK+DEVICE", ignoreCase = true) -> AtResponse.DeviceInfo(
                text.substringAfter("[", "").substringBefore("]")
            )
            text.startsWith("OK+TLVOICEOFF:", ignoreCase = true) -> {
                val raw = text.substringAfter(":", "").trim()
                AtResponse.VoiceOffState(raw == "1")
            }
            text.startsWith("OK", ignoreCase = true)       -> AtResponse.Ok(text)
            else -> AtResponse.Unknown(text)
        }
    }
}

sealed interface AtResponse {
    data object PinOk : AtResponse
    data object PinRejected : AtResponse
    data object PinChanged : AtResponse
    data class DeviceInfo(val model: String) : AtResponse
    data class VoiceOffState(val on: Boolean) : AtResponse
    data class Ok(val text: String) : AtResponse
    data class Unknown(val text: String) : AtResponse
}
