package eu.dns3d.epfcockpit.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import eu.dns3d.epfcockpit.service.EpfConnectionService

/**
 * Quick-Tile (Status-Bar Schnelleinstellungen): Frontlicht ein/aus — das
 * Gegenstück zum [UnlockTileService]. Zustand kommt aus dem
 * [WidgetStateHolder], der Klick togglet via ACTION_TOGGLE_LIGHT.
 *
 * Das Licht lässt sich nur bei entsperrtem Roller schalten (geparkt ignoriert
 * der Controller das Licht-Bit). Geparkt ist die Kachel deshalb ausgegraut;
 * nicht verbunden verbindet der Tap nur.
 */
class LightTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val ready = WidgetStateHolder.connected && WidgetStateHolder.authenticated
        // Geparkt bzw. Zustand noch unbekannt: nichts zu schalten.
        if (ready && WidgetStateHolder.parking != false) return
        val intent = Intent(this, EpfConnectionService::class.java).apply {
            action = if (ready) EpfConnectionService.ACTION_TOGGLE_LIGHT else EpfConnectionService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        // Optimistisch flippen — der FGS pusht den echten Zustand per requestRefresh nach.
        if (ready) {
            applyState(
                light = !(WidgetStateHolder.lightOn ?: false),
                parking = false, connected = true, auth = true,
            )
        }
    }

    private fun refresh() {
        applyState(
            light = WidgetStateHolder.lightOn,
            parking = WidgetStateHolder.parking,
            connected = WidgetStateHolder.connected,
            auth = WidgetStateHolder.authenticated,
        )
    }

    private fun applyState(light: Boolean?, parking: Boolean?, connected: Boolean, auth: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Roller-Licht"
        when {
            !connected || !auth -> {
                // NICHT STATE_UNAVAILABLE: SystemUI verwirft Klicks auf solche
                // Tiles — im getrennten Zustand soll der Tap aber verbinden.
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Verbinden"
            }
            parking != false -> {
                // Hier soll der Tap bewusst nichts tun.
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = if (parking == true) "Geparkt" else "–"
            }
            light == true -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "An"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Aus"
            }
        }
        tile.updateTile()
    }

    companion object {
        /** Vom FGS aufgerufen damit der Tile sich beim nächsten Sichtbarwerden frisch zeichnet. */
        fun requestRefresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, LightTileService::class.java))
            } catch (_: Throwable) {
                // Tile ist nicht aktiv im Panel oder Service nicht gebunden — ignore.
            }
        }
    }
}
