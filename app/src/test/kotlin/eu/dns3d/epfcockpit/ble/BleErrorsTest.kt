package eu.dns3d.epfcockpit.ble

import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class BleErrorsTest {

    @Test
    fun `GATT-Statuscodes werden als Zahl und als Name erkannt`() {
        val unreachable = "Scooter nicht erreichbar – ist er eingeschaltet?"
        assertEquals(unreachable, BleErrors.friendlyError("Error on connection state change: status 8"))
        assertEquals(unreachable, BleErrors.friendlyError("GATT_CONN_TIMEOUT"))
        assertEquals("Scooter hat die Verbindung beendet", BleErrors.friendlyError("status 19"))
        assertEquals("Signal zu schwach – näher an den Scooter", BleErrors.friendlyError("GATT_CONN_LMP_TIMEOUT"))
        assertEquals("Verbindung konnte nicht aufgebaut werden", BleErrors.friendlyError("status 62"))
    }

    @Test
    fun `RequestFailedException nennt den Code`() {
        assertEquals(
            "Verbindung fehlgeschlagen (Code 133)",
            BleErrors.friendlyError("RequestFailedException: Request failed with status: 133"),
        )
        assertEquals("Verbindung fehlgeschlagen", BleErrors.friendlyError("RequestFailedException"))
    }

    @Test
    fun `Fallbacks`() {
        assertEquals("Zeitüberschreitung", BleErrors.friendlyError("Read timeout"))
        assertEquals("Unbekannter Verbindungsfehler", BleErrors.friendlyError("  "))
        assertEquals("irgendwas", BleErrors.friendlyError("irgendwas"))
        assertEquals("IllegalStateException", BleErrors.friendlyError(IllegalStateException()))
    }

    @Test
    fun `Disconnect-Gründe`() {
        assertEquals(
            "Scooter hat die Verbindung beendet",
            BleErrors.friendlyDisconnectReason(ConnectionObserver.REASON_TERMINATE_PEER_USER),
        )
        assertEquals(
            "Verbindung verloren – Signal weg",
            BleErrors.friendlyDisconnectReason(ConnectionObserver.REASON_TIMEOUT),
        )
        assertEquals("Verbindung unterbrochen (reason=99)", BleErrors.friendlyDisconnectReason(99))
    }
}
