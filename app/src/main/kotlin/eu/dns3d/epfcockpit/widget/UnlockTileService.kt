package eu.dns3d.epfcockpit.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import eu.dns3d.epfcockpit.service.EpfConnectionService

/**
 * Quick-Tile (Status-Bar Schnelleinstellungen): 1-Klick Roller entsperren /
 * sperren, je nach aktuellem Park-Zustand. Label und Status werden aus dem
 * [WidgetStateHolder] gelesen (vom FGS bei jedem Telemetrie-Tick aktualisiert),
 * der Klick togglet via ACTION_TOGGLE_PARK.
 *
 * Lifecycle: onStartListening läuft sobald der User die Schnelleinstellungen
 * öffnet (Tile sichtbar). onClick darf direkt einen Service-Intent feuern,
 * der startForegroundService geht auch wenn die App grad nicht im Vordergrund
 * ist (Tile-Klick zählt als User-Interaktion, kein Background-Restriction).
 */
class UnlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, EpfConnectionService::class.java).apply {
            action = EpfConnectionService.ACTION_TOGGLE_PARK
        }
        ContextCompat.startForegroundService(this, intent)
        // Optimistisch das Label flippen — der FGS pusht nach erfolgreichem
        // BLE-Roundtrip nochmal den echten Status via requestRefresh.
        val parking = WidgetStateHolder.parking
        if (WidgetStateHolder.connected && parking != null) {
            applyState(parking = !parking, connected = true, auth = WidgetStateHolder.authenticated)
        }
    }

    private fun refresh() {
        applyState(
            parking = WidgetStateHolder.parking,
            connected = WidgetStateHolder.connected,
            auth = WidgetStateHolder.authenticated,
        )
    }

    private fun applyState(parking: Boolean?, connected: Boolean, auth: Boolean) {
        val tile = qsTile ?: return
        when {
            !connected || !auth -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "E-Scooter"
                tile.subtitle = "nicht verbunden"
            }
            parking == true -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "E-Scooter"
                tile.subtitle = "Entsperren"
            }
            parking == false -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "E-Scooter"
                tile.subtitle = "Sperren"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "E-Scooter"
                tile.subtitle = "Tippen"
            }
        }
        tile.updateTile()
    }

    companion object {
        /** Vom FGS aufgerufen damit der Tile sich beim nächsten Sichtbarwerden frisch zeichnet. */
        fun requestRefresh(context: Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, UnlockTileService::class.java),
                )
            } catch (_: Throwable) {
                // Tile ist nicht aktiv im Panel oder Service nicht gebunden — ignore.
            }
        }
    }
}
