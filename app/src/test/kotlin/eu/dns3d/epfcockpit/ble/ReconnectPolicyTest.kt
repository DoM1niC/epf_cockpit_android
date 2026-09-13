package eu.dns3d.epfcockpit.ble

import eu.dns3d.epfcockpit.ble.ReconnectPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wahrheitstabelle der Reconnect-Policy. Schützt vor allem den
 * Single-Link-Handover (Commit 360c8d4): entsperrt + andere App vorne darf den
 * Roller nie aktiv greifen.
 */
class ReconnectPolicyTest {

    private fun decide(
        bluetoothOn: Boolean = true,
        force: Boolean = false,
        appForeground: Boolean = false,
        reconnectWhileLocked: Boolean = true,
        backgroundEligible: Boolean = true,
        flapping: Boolean = false,
        deviceLocked: Boolean = false,
        ridingRecently: Boolean = false,
    ) = ReconnectPolicy.decide(
        bluetoothOn, force, appForeground, reconnectWhileLocked,
        backgroundEligible, flapping, deviceLocked, ridingRecently,
    )

    @Test fun bluetoothOff_waitsForBluetooth_whenReconnectWouldBeAllowed() {
        assertEquals(Mode.BLUETOOTH_OFF, decide(bluetoothOn = false))
        assertEquals(Mode.BLUETOOTH_OFF, decide(bluetoothOn = false, deviceLocked = true))
        assertEquals(Mode.BLUETOOTH_OFF, decide(bluetoothOn = false, force = true))
        assertEquals(Mode.BLUETOOTH_OFF, decide(bluetoothOn = false, appForeground = true))
        assertEquals(Mode.BLUETOOTH_OFF, decide(bluetoothOn = false, appForeground = true, reconnectWhileLocked = false))
    }

    @Test fun bluetoothOff_passiveWithoutPermission_defers() {
        assertEquals(Mode.DEFER, decide(bluetoothOn = false, reconnectWhileLocked = false))
        assertEquals(Mode.DEFER, decide(bluetoothOn = false, backgroundEligible = false))
        assertEquals(Mode.DEFER, decide(bluetoothOn = false, flapping = true))
    }

    @Test fun foregroundOrForce_isActive_evenWithoutBackgroundPermission() {
        assertEquals(Mode.ACTIVE, decide(appForeground = true))
        assertEquals(Mode.ACTIVE, decide(force = true))
        assertEquals(Mode.ACTIVE, decide(appForeground = true, reconnectWhileLocked = false))
        assertEquals(Mode.ACTIVE, decide(force = true, backgroundEligible = false, flapping = true))
    }

    @Test fun lockedPhone_waits() {
        assertEquals(Mode.WAIT, decide(deviceLocked = true))
    }

    @Test fun ridingWithUnlockedPhone_waits() {
        assertEquals(Mode.WAIT, decide(ridingRecently = true))
    }

    @Test fun unlockedWithOtherAppInFront_pauses_forHandover() {
        assertEquals(Mode.PAUSE, decide(deviceLocked = false, ridingRecently = false))
    }

    @Test fun switchOff_restoresOldBehavior() {
        assertEquals(Mode.DEFER, decide(reconnectWhileLocked = false, deviceLocked = true))
        assertEquals(Mode.DEFER, decide(reconnectWhileLocked = false, ridingRecently = true))
    }

    @Test fun neverAuthenticated_orFlapping_defers() {
        assertEquals(Mode.DEFER, decide(backgroundEligible = false, deviceLocked = true))
        assertEquals(Mode.DEFER, decide(flapping = true, deviceLocked = true))
    }

    @Test fun backoff_steps() {
        assertEquals(10_000L, ReconnectPolicy.backoffMs(1))
        assertEquals(20_000L, ReconnectPolicy.backoffMs(2))
        assertEquals(30_000L, ReconnectPolicy.backoffMs(3))
        assertEquals(60_000L, ReconnectPolicy.backoffMs(4))
        assertEquals(60_000L, ReconnectPolicy.backoffMs(9))
    }
}
