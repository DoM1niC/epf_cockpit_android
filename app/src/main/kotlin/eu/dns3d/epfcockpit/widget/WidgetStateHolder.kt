package eu.dns3d.epfcockpit.widget

/**
 * Letzter bekannter Roller-Zustand für Widget + Quick-Tile. Wird vom
 * [eu.dns3d.epfcockpit.service.EpfConnectionService] bei jedem Telemetrie-
 * Tick aktualisiert. TileService.onStartListening liest den Snapshot um
 * sofort den richtigen Label zu zeigen, statt erst auf den nächsten Push
 * zu warten.
 */
object WidgetStateHolder {
    @Volatile var connected: Boolean = false
        private set
    @Volatile var authenticated: Boolean = false
        private set
    @Volatile var parking: Boolean? = null
        private set
    @Volatile var rideModeStand: Boolean = true
        private set

    fun update(
        connected: Boolean,
        authenticated: Boolean,
        parking: Boolean?,
        rideModeStand: Boolean,
    ) {
        this.connected = connected
        this.authenticated = authenticated
        this.parking = parking
        this.rideModeStand = rideModeStand
    }
}
