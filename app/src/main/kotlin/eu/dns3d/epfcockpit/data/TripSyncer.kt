package eu.dns3d.epfcockpit.data

import android.util.Log
import eu.dns3d.epfcockpit.streaming.StreamMessage
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backfill aller in Room gespeicherten Trips an den Web-Server.
 *
 * Warum: Trips entstehen lokal auch wenn das Streaming gerade nicht
 * verbunden ist (Mobilfunkloch, Server down, Streaming-Switch off). Sobald
 * der WebSocket wieder hochkommt, pushen wir die komplette Trip-Historie
 * nochmal — Server macht Insert-or-Update per Trip-ID, also ist das
 * idempotent. Die IDs sind [TripCoordinator.LOCAL_ID_PREFIX] + Room-PK,
 * gleich für live-finalisierte und nachsynchronisierte Trips.
 */
@Singleton
class TripSyncer @Inject constructor(
    private val tripDao: TripDao,
    private val streamer: TelemetryStreamer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            streamer.status
                .filter { it == TelemetryStreamer.Status.Connected }
                .collect {
                    // Kurze Pause damit der hello-Frame zuerst durch ist —
                    // sonst landen Trip-Events ggf. vor hello und der
                    // Server hat lastScooterName=null als Fallback.
                    delay(1_000)
                    runCatching { syncAll() }
                        .onFailure { Log.w(TAG, "syncAll failed: ${it.message}") }
                }
        }
    }

    private suspend fun syncAll() {
        val trips = tripDao.getAll()
        if (trips.isEmpty()) {
            Log.i(TAG, "no trips to sync")
            return
        }
        Log.i(TAG, "syncing ${trips.size} trips")
        for (trip in trips) {
            val tripId = TripCoordinator.LOCAL_ID_PREFIX + trip.id
            streamer.send(
                StreamMessage.TripEnd(
                    tripId = tripId,
                    startedAt = trip.startedAt,
                    ts = trip.endedAt,
                    distanceKm = trip.distanceKm.toDouble(),
                    energyWh = trip.energyWh.toDouble(),
                    durationS = ((trip.endedAt - trip.startedAt) / 1000).coerceAtLeast(0),
                    maxSpeedKmh = trip.maxSpeedKmh.toDouble(),
                    avgSpeedKmh = trip.avgSpeedKmh.toDouble(),
                    scooterAddress = trip.scooterAddress,
                )
            )
            // 20 ms Gap → Socket nicht fluten, alles bleibt unter ~50 Trips/s.
            delay(20)
        }
        Log.i(TAG, "sync done")
    }

    private companion object {
        const val TAG = "TripSyncer"
    }
}
