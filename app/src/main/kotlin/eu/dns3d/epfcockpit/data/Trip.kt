package eu.dns3d.epfcockpit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val distanceKm: Float,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val energyWh: Float,
    val startBatteryPct: Int,
    val endBatteryPct: Int,
    val scooterAddress: String,
    /**
     * Zeitpunkt (ms), zu dem der Web-Server diese Fahrt per `trip_ack`
     * quittiert hat; 0 = noch nicht synchronisiert. Der [TripSyncer] schickt
     * nur Fahrten mit 0 nach.
     */
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,
)
