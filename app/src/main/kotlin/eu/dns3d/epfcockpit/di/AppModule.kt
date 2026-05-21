package eu.dns3d.epfcockpit.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.dns3d.epfcockpit.data.TripDao
import eu.dns3d.epfcockpit.data.TripDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideTripDatabase(@ApplicationContext context: Context): TripDatabase =
        Room.databaseBuilder(context, TripDatabase::class.java, "trips.db").build()

    @Provides
    fun provideTripDao(db: TripDatabase): TripDao = db.tripDao()
}
