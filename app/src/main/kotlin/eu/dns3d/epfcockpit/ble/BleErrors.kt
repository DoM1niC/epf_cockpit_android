package eu.dns3d.epfcockpit.ble

import no.nordicsemi.android.ble.observer.ConnectionObserver

/** Übersetzt BLE-Fehler in Texte, die im UI stehen dürfen. Reine Funktionen. */
internal object BleErrors {

    fun friendlyError(t: Throwable): String =
        friendlyError((t.message ?: t::class.java.simpleName).orEmpty())

    fun friendlyError(raw: String): String {
        // Nordic surfaces GATT status codes either as numeric ("status 8")
        // or as enum names ("GATT_CONN_TIMEOUT"). We catch both.
        return when {
            raw.contains("CONN_TIMEOUT", true) || raw.contains("status 8") ->
                "Scooter nicht erreichbar – ist er eingeschaltet?"
            raw.contains("TERMINATE_PEER_USER", true) || raw.contains("status 19") ->
                "Scooter hat die Verbindung beendet"
            raw.contains("CONN_TERMINATE", true) || raw.contains("status 22") ->
                "Verbindung unterbrochen"
            raw.contains("CONN_LMP_TIMEOUT", true) || raw.contains("status 34") ->
                "Signal zu schwach – näher an den Scooter"
            raw.contains("CONN_FAIL_ESTABLISH", true) || raw.contains("status 62") ->
                "Verbindung konnte nicht aufgebaut werden"
            raw.contains("ConnectionRequest", true) && raw.contains("cancelled", true) ->
                "Verbindungsversuch abgebrochen"
            raw.contains("DeviceDisconnectedException", true) ->
                "Scooter hat die Verbindung beendet"
            raw.contains("RequestFailedException", true) -> {
                val status = Regex("status: (-?\\d+)").find(raw)?.groupValues?.get(1)
                if (status != null) "Verbindung fehlgeschlagen (Code $status)"
                else "Verbindung fehlgeschlagen"
            }
            raw.contains("timeout", true) -> "Zeitüberschreitung"
            raw.isBlank() -> "Unbekannter Verbindungsfehler"
            else -> raw
        }
    }

    fun friendlyDisconnectReason(reason: Int): String = when (reason) {
        ConnectionObserver.REASON_TERMINATE_PEER_USER -> "Scooter hat die Verbindung beendet"
        ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> "Verbindung vom Handy beendet"
        ConnectionObserver.REASON_LINK_LOSS -> "Verbindung verloren – Signal weg"
        ConnectionObserver.REASON_TIMEOUT -> "Verbindung verloren – Signal weg"
        ConnectionObserver.REASON_NOT_SUPPORTED -> "Gerät nicht unterstützt"
        ConnectionObserver.REASON_CANCELLED -> "Verbindungsversuch abgebrochen"
        else -> "Verbindung unterbrochen (reason=$reason)"
    }
}
