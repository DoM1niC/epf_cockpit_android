package eu.dns3d.epfcockpit.data

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
)
