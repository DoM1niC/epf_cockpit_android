package eu.dns3d.epfcockpit.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Trip::class, TripLocation::class], version = 2, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        /**
         * v1 → v2: trip_locations-Tabelle für optionales GPS-Tracking. Bestehende
         * Trips-Tabelle bleibt unverändert; wir wollen die Historie behalten —
         * deshalb manuelles Migration-Skript statt fallbackToDestructiveMigration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trip_locations` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`tripRowId` INTEGER NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`lat` REAL NOT NULL, " +
                        "`lon` REAL NOT NULL, " +
                        "`accuracyM` REAL, " +
                        "`speedMps` REAL, " +
                        "FOREIGN KEY(`tripRowId`) REFERENCES `trips`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trip_locations_tripRowId_ts` " +
                        "ON `trip_locations` (`tripRowId`, `ts`)"
                )
            }
        }
    }
}
