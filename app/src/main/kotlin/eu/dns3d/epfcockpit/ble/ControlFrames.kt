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
    // Pulse Plus uses a flags byte where:
    //   bit 7 = ride mode  (set = Stand-Stop, clear = Kick-Start)
    //   bit 5 = park       (set = parked,   clear = active)
    //   bits 1..0 = gear   (always observed as 2 in sniffs)
    //
    // Sniff-verified flag combinations:
    //   0x82  =  Active + Stand   → "Parking OFF" / "Mode Kick" (official app
    //                                reuses this single frame for both actions)
    //   0x22  =  Parked + Kick    → "Parking ON"
    //   0xA2  =  Parked + Stand   → "Mode Stand-Stop" (also what the EPF app
    //                                sends when the user changes the speed slider)
    //
    // All frames carry the canonical payload tail `… 06 0F <g3> <CRC-LE>`
    // where g3 is the gear-3 max km/h. The official app hardcodes 0x14 = 20 km/h,
    // but the controller accepts higher values byte-for-byte (sniff #3 observed
    // g3 = 0x16 = 22 km/h being sent by the original app when its slider sits at
    // its top notch — i.e. the EPF app adds its own +2 tolerance offset over
    // the displayed value, the controller just gets the raw byte).
    //
    // We expose builder functions instead of static byte arrays so the user's
    // saved max-speed survives a park/unpark or mode toggle — otherwise every
    // toggle would reset g3 back to 20.

    private const val PULSE_GEAR1_KMH: Byte = 6
    private const val PULSE_GEAR2_KMH: Byte = 15

    /**
     * Builds a Pulse-Plus state frame: `AF 00 0A <flags> 00 06 0F <g3> <CRC-LE>`.
     * Range-checks g3 to 1..40 — controller will likely clamp anything silly,
     * but we don't want to send 0 (would mean "stop dead") or negative bytes.
     *
     * Verwendet [Crc16Modbus.appendLE] — Pulse Plus erwartet CRC little-endian.
     * Mit BE-CRC (wie ZydDash/`append`) verwirft der Hobbywing-Controller die
     * Frames stumm; Telemetry-statusByte bleibt konstant, Park/Mode/Speed tut nix.
     */
    private fun pulseFrame(flags: Byte, g3MaxKmh: Int): ByteArray {
        require(g3MaxKmh in 1..40) { "g3MaxKmh out of range: $g3MaxKmh" }
        val payload = byteArrayOf(
            BleConstants.FRAME_HEADER_EPF, 0x00, 0x0A, flags, 0x00,
            PULSE_GEAR1_KMH, PULSE_GEAR2_KMH, g3MaxKmh.toByte(),
        )
        return Crc16Modbus.appendLE(payload)
    }

    /** Parking OFF — unlock the scooter from P-mode (also = Mode Kick-Start). */
    fun parkingOff(g3MaxKmh: Int = 20): ByteArray = pulseFrame(0x82.toByte(), g3MaxKmh)

    /** Parking ON — engage the park lock. */
    fun parkingOn(g3MaxKmh: Int = 20): ByteArray = pulseFrame(0x22.toByte(), g3MaxKmh)

    /** Ride mode Stand-Stop — parks AND switches to stand mode in one frame. */
    fun modeStandStop(g3MaxKmh: Int = 20): ByteArray = pulseFrame(0xA2.toByte(), g3MaxKmh)

    /** Ride mode Kick-Start — byte-identical to [parkingOff] (unparks + kick mode). */
    fun modeKickStart(g3MaxKmh: Int = 20): ByteArray = parkingOff(g3MaxKmh)

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
