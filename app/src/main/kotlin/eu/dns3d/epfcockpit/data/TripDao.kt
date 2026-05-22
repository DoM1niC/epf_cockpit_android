package eu.dns3d.epfcockpit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY endedAt DESC")
    fun observeAll(): Flow<List<Trip>>

    /** One-shot read für den TripSyncer (Backfill an den Web-Server). */
    @Query("SELECT * FROM trips ORDER BY endedAt ASC")
    suspend fun getAll(): List<Trip>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trips")
    suspend fun clear()
}
