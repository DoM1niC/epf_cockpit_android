package eu.dns3d.epfcockpit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    // `endedAt > 0` filtert in-progress-Trips raus: der TripCoordinator legt
    // bei erstem Telemetrie-Sample eine Placeholder-Row an (endedAt=0) damit
    // der LocationCollector die Row-ID für die Foreign-Key-Verknüpfung der
    // Waypoints hat. Finalisiert wird sie erst beim Disconnect mit dem echten
    // endedAt — vorher gehört der Trip nicht in die History-UI.
    @Query("SELECT * FROM trips WHERE endedAt > 0 ORDER BY endedAt DESC")
    fun observeAll(): Flow<List<Trip>>

    /** One-shot read für den TripSyncer (Backfill an den Web-Server). */
    @Query("SELECT * FROM trips WHERE endedAt > 0 ORDER BY endedAt ASC")
    suspend fun getAll(): List<Trip>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trips")
    suspend fun clear()

    /**
     * Cleanup für abgebrochene Trips (App-Crash mit-mit-Trip, Force-Stop usw.):
     * löscht alle Placeholder-Rows die nie finalisiert wurden. Wird beim
     * Coordinator-Startup einmal aufgerufen. Cascade entfernt auch die Locations.
     */
    @Query("DELETE FROM trips WHERE endedAt = 0")
    suspend fun deleteOrphans()

    // --- TripLocation ----------------------------------------------------------
    // GPS-Waypoints werden per FK an [Trip.id] gehängt. CASCADE-Delete in der
    // Entity sorgt dafür, dass die Waypoints beim Trip-Delete automatisch
    // verschwinden — wir müssen keinen extra Cleanup aufrufen.

    @Insert
    suspend fun insertLocation(location: TripLocation): Long

    @Query("SELECT * FROM trip_locations WHERE tripRowId = :tripRowId ORDER BY ts ASC")
    suspend fun locationsForTrip(tripRowId: Long): List<TripLocation>
}
