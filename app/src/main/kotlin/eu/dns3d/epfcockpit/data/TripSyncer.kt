package eu.dns3d.epfcockpit.data

import android.util.Log
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backfill der in Room gespeicherten Trips an den Web-Server.
 *
 * Warum: Trips entstehen lokal auch wenn das Streaming gerade nicht
 * verbunden ist (Mobilfunkloch, Server down, Streaming-Switch off). Sobald
 * der WebSocket wieder hochkommt, schicken wir alle Fahrten nach, die der
 * Server noch nicht quittiert hat.
 *
 * Der Server antwortet auf jedes verarbeitete `trip_end` mit `trip_ack`;
 * erst dann setzen wir [Trip.syncedAt]. Früher ging bei JEDEM Connect die
 * komplette Historie samt aller Waypoints erneut raus — bei wackeligem
 * Mobilfunk also dutzendfach pro Fahrt. Ein Server ohne `trip_ack` (alte
 * Version) bekommt weiterhin alles bei jedem Connect, das ist idempotent.
 *
 * Die IDs sind [TripCoordinator.LOCAL_ID_PREFIX] + Room-PK, gleich für
 * live-finalisierte und nachsynchronisierte Trips.
 */
@Singleton
class TripSyncer @Inject constructor(
    private val tripDao: TripDao,
    private val streamer: TelemetryStreamer,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            streamer.tripAcks.collect { tripId ->
                val rowId = tripId.removePrefix(TripCoordinator.LOCAL_ID_PREFIX).toLongOrNull()
                    ?.takeIf { tripId.startsWith(TripCoordinator.LOCAL_ID_PREFIX) }
                    ?: return@collect
                runCatching { tripDao.markSynced(rowId, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "markSynced failed: ${it.message}") }
            }
        }
        // Andere Server-URL = anderer Server: dort fehlt die Historie.
        scope.launch {
            settings.flow
                .map { it.streamingUrl }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    runCatching { tripDao.resetSynced() }
                        .onFailure { Log.w(TAG, "resetSynced failed: ${it.message}") }
                }
        }
        scope.launch {
            // collectLatest: flattert der Socket, bricht ein laufender Sync ab,
            // statt dass sich mehrere überlagern.
            streamer.status.collectLatest { status ->
                if (status != TelemetryStreamer.Status.Connected) return@collectLatest
                // Kurze Pause: hello ist durch, und eine gerade live
                // finalisierte Fahrt hat ihr trip_ack schon bekommen.
                delay(2_000)
                runCatching { syncPending() }
                    .onFailure { Log.w(TAG, "sync failed: ${it.message}") }
            }
        }
    }

    private suspend fun syncPending() {
        val trips = tripDao.getUnsynced()
        if (trips.isEmpty()) return
        Log.i(TAG, "syncing ${trips.size} trips")
        for (trip in trips) {
            // GPS-Waypoints inline mitliefern — der Server kann sich beim
            // Backfill nicht auf Live-`location`-Frames verlassen.
            val waypoints = runCatching { tripDao.locationsForTrip(trip.id) }
                .getOrElse { emptyList() }
            streamer.send(trip.toTripEnd(waypoints))
            // Kleiner Gap → Socket nicht fluten.
            delay(20)
        }
        Log.i(TAG, "sync sent")
    }

    private companion object {
        const val TAG = "TripSyncer"
    }
}
