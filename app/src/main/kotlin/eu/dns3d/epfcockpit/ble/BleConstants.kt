package eu.dns3d.epfcockpit.ble

import java.util.UUID

/**
 * BLE service & characteristic UUIDs reverse-engineered for ePF-1 / ePowerFun scooters
 * (Zydtech HW9027 controller). Documented in ZydDash:
 * https://github.com/Ennar1991/ZydDash/blob/main/BLE_Telemetry.md
 *
 * For the EPF Pulse Plus (Hobbywing controller) compatibility has NOT been verified
 * publicly — the UUIDs are likely identical (same vendor app), but the frame layout
 * may differ. Verify with nRF Connect before trusting the decoded values.
 */
object BleConstants {

    // Low-level service: telemetry (notify) + control (write)
    val LOW_SERVICE: UUID = UUID.fromString("0000f1f0-0000-1000-8000-00805f9b34fb")
    val LOW_TX_CHAR:  UUID = UUID.fromString("0000f1f1-0000-1000-8000-00805f9b34fb")
    val LOW_RX_CHAR:  UUID = UUID.fromString("0000f1f2-0000-1000-8000-00805f9b34fb")

    // High-level service: AT-style commands (password, device name)
    val HIGH_SERVICE: UUID = UUID.fromString("0000f2f0-0000-1000-8000-00805f9b34fb")
    val HIGH_TX_CHAR: UUID = UUID.fromString("0000f2f1-0000-1000-8000-00805f9b34fb")
    val HIGH_RX_CHAR: UUID = UUID.fromString("0000f2f2-0000-1000-8000-00805f9b34fb")

    const val FRAME_HEADER_EPF: Byte = 0xAF.toByte()
    const val FRAME_HEADER_ALT: Byte = 0xAB.toByte()

    const val DEFAULT_PIN: String = "888888"

    // Names the scooter typically advertises with.
    //   ePF / EPF / ePower → fully provisioned units running ZydDash
    //   HW_                → Hobbywing default name (e.g. EPF Pulse Plus
    //                        ships advertising "HW_<serial>")
    //   ZYD                → Zydtech default
    val ADVERTISED_NAME_PREFIXES = listOf("ePF", "EPF", "ePower", "ZYD", "HW_")
}
