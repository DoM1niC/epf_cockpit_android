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

    // === Pulse-Plus-Statusframe ===
    //
    // `AF 00 0A <flags> 00 06 0F <g3> <CRC-LE>` — der Controller bekommt immer
    // den KOMPLETTEN Soll-Zustand, kein Delta. Bit-Layout des Flags-Bytes laut
    // dekompilierter offizieller App (`BleCore.setBaseParams` → `sendMonitor`,
    // Bits LSB-first aus `BaseParams`):
    //
    //   bit 0..1  gearPosition       (wir senden immer 2, wie die offizielle App)
    //   bit 2     headLightSw        Frontlicht
    //   bit 3     atmosphereLightSw  beim Pulse Plus = Tageskilometer-Reset
    //                                (SettingsFragment „reset_distance" setzt genau
    //                                dieses Bit; ZydDash dokumentiert bit3 = trip reset)
    //   bit 4     cruiseControlSw
    //   bit 5     bootMode           (Zydtech-Name; bei uns Teil von Park/Stand)
    //   bit 6     metricInchSw
    //   bit 7     lockSw
    //
    // Sniff-verifizierte Park/Mode-Kombinationen (Licht aus):
    //   0x22  Parking ON
    //   0x82  Kick-Start  (= „Parking OFF" der offiziellen App)
    //   0xA2  Stand-Stop  (auch das, was die EPF-App beim Speed-Slider sendet)
    //
    // Weil jedes Frame den kompletten Zustand trägt, MUSS das Licht-Bit in jedem
    // Mode/Speed-Frame mitgeschickt werden — sonst schaltet z.B. ein Mode-Wechsel
    // das Licht ungewollt aus.
    //
    // Licht live verifiziert am Pulse Plus (HW_EF043840, Nov 2026):
    //   - entsperrt: 0xA6 / 0x86 schaltet das Licht ein, 0xA2 / 0x82 aus;
    //     Telemetrie-Bit 2 (`headLightSw`) folgt nach ~0,5 s.
    //   - geparkt: Bit 2 wird ignoriert, und beim Parken schaltet der
    //     Controller das Licht selbst ab. Park-Frames senden das Bit daher
    //     nie — sie bleiben byte-identisch mit dem gesnifften 0x22.
    //
    // g3 = Max-km/h für Gang 3. Die offizielle App hardcodet 0x14 = 20 km/h, der
    // Controller nimmt höhere Werte aber byteweise an (Echo-Sync, siehe README).

    private const val PULSE_GEAR1_KMH: Byte = 6
    private const val PULSE_GEAR2_KMH: Byte = 15

    const val FLAG_GEAR_2 = 0x02
    const val FLAG_LIGHT = 0x04
    const val FLAG_TRIP_RESET = 0x08
    const val FLAG_PARKED = 0x20          // 0x22 = Parking ON
    const val FLAG_KICK = 0x80            // 0x82 = Kick-Start
    const val FLAG_STAND = 0xA0           // 0xA2 = Stand-Stop

    /**
     * Baut ein Pulse-Plus-Statusframe aus dem gewünschten Gesamt-Zustand.
     *
     * @param parked   true → Parking ON (Mode wird dabei nicht kodiert, wie 0x22)
     * @param stand    nur relevant wenn nicht geparkt: Stand-Stop vs Kick-Start
     * @param light    Frontlicht an (nur entsperrt wirksam, geparkt nie gesendet)
     * @param resetTrip Tageskilometer zurücksetzen (einmalig, danach wieder ohne senden)
     */
    fun pulseState(
        parked: Boolean,
        stand: Boolean,
        light: Boolean = false,
        resetTrip: Boolean = false,
        g3MaxKmh: Int = 20,
    ): ByteArray {
        var flags = FLAG_GEAR_2 or when {
            parked -> FLAG_PARKED
            stand -> FLAG_STAND
            else -> FLAG_KICK
        }
        if (light && !parked) flags = flags or FLAG_LIGHT
        if (resetTrip) flags = flags or FLAG_TRIP_RESET
        return pulseFrame(flags.toByte(), g3MaxKmh)
    }

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
    fun parkingOff(g3MaxKmh: Int = 20, light: Boolean = false): ByteArray =
        pulseState(parked = false, stand = false, light = light, g3MaxKmh = g3MaxKmh)

    /** Parking ON — engage the park lock. */
    fun parkingOn(g3MaxKmh: Int = 20, light: Boolean = false): ByteArray =
        pulseState(parked = true, stand = false, light = light, g3MaxKmh = g3MaxKmh)

    /** Ride mode Stand-Stop (0xA2). */
    fun modeStandStop(g3MaxKmh: Int = 20, light: Boolean = false): ByteArray =
        pulseState(parked = false, stand = true, light = light, g3MaxKmh = g3MaxKmh)

    /** Ride mode Kick-Start — byte-identical to [parkingOff] (unparks + kick mode). */
    fun modeKickStart(g3MaxKmh: Int = 20, light: Boolean = false): ByteArray =
        parkingOff(g3MaxKmh, light)

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
