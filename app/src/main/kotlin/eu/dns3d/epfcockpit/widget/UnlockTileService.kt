package eu.dns3d.epfcockpit.widget

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import eu.dns3d.epfcockpit.service.EpfConnectionService

/**
 * Quick-Tile (Status-Bar Schnelleinstellungen): 1-Klick Roller entsperren.
 *
 * Der Tile-Click feuert einen Service-Intent mit ACTION_UNLOCK; der FGS
 * uebernimmt das Warten auf BLE-Auth und das eigentliche setParking(false).
 */
class UnlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "E-Scooter entsperren"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, EpfConnectionService::class.java).apply {
            action = EpfConnectionService.ACTION_UNLOCK
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
