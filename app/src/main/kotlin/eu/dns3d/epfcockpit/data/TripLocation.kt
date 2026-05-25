package eu.dns3d.epfcockpit.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ein einzelner GPS-Waypoint einer Fahrt. Wird vom [LocationCollector] alle
 * 5 Sekunden eingefügt, solange ein Trip läuft, der User Standortverfolgung
 * eingeschaltet hat UND der Roller nicht parkt.
 *
 * Der Foreign-Key auf [Trip.id] ist mit `onDelete = CASCADE` verdrahtet — wenn
 * ein Trip aus History gelöscht wird, gehen seine Waypoints automatisch mit.
 * Index `(tripRowId, ts)` deckt sowohl die FK-Lookups als auch die zeitlich
 * sortierte Query ab, die [TripDao.locationsForTrip] braucht.
 */
@Entity(
    tableName = "trip_locations",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripRowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripRowId", "ts"])],
)
data class TripLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripRowId: Long,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    /** Horizontale Genauigkeit in Metern (Provider-Angabe), null wenn unbekannt. */
    val accuracyM: Float?,
    /** Geschwindigkeit über Grund in m/s (Provider-Angabe), null wenn unbekannt. */
    val speedMps: Float?,
)
