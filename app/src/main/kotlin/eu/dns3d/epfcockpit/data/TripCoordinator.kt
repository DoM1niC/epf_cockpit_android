package eu.dns3d.epfcockpit.data

import android.util.Log
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.streaming.StreamMessage
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Recordet Fahrten unabhängig von der UI-Lifecycle. Der frühere Code im
 * DashboardViewModel hat das Recording finalisiert sobald die VM zerstört
 * wurde — d.h. sobald man zu Settings/History navigiert hat oder der
 * Screen aus ging.
 *
 * Hier hängt das Recording an [BleRepository.connection]:
 *   - Connected → neuer TripRecorder + UUID pro Verbindung
 *   - Telemetry-Frame → `recorder.onSample`
 *     Beim ersten Sample wird zusätzlich eine Placeholder-Row in Room
 *     angelegt (endedAt=0) — sonst hat der [LocationCollector] keine
 *     Trip-Row-ID für die Foreign-Key-Verknüpfung der GPS-Waypoints. Die
 *     Row wird beim Disconnect mit den finalen Werten überschrieben oder
 *     (bei <10 m) gelöscht.
 *   - Disconnected → finalize:
 *       * lokal: in Room nur wenn ≥10 m (sonst Geister-Trip → DELETE der
 *         Placeholder-Row, Cascade entfernt auch evtl. Waypoints)
 *       * remote: trip_end-Event an [TelemetryStreamer] mit allen Stats
 *         + den GPS-Waypoints inline, self-contained (Server macht
 *         Insert-or-Update)
 *
 * Wird als @Singleton von [eu.dns3d.epfcockpit.MainActivity] eagerly
 * injected, damit das Recording schon ab App-Start aktiv ist.
 */
@Singleton
class TripCoordinator @Inject constructor(
    private val ble: BleRepository,
    private val tripDao: TripDao,
    private val streamer: TelemetryStreamer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: TripRecorder? = null
    private var currentScooterAddress: String? = null

    private val _activeTripRowId = MutableStateFlow<Long?>(null)
    /**
     * Row-ID des gerade laufenden Trips, sobald das erste Telemetrie-Sample
     * eingegangen ist. Null wenn kein Trip aktiv ist (Disconnected, oder
     * verbunden aber noch kein Telemetrie-Frame angekommen). Wird vom
     * [LocationCollector] beobachtet — startet/stoppt GPS-Updates.
     *
     * Die ID ist die Room-PK und damit der Suffix für `local-<id>`-Trip-IDs,
     * die der Web-Server als Join-Key benutzt.
     */
    val activeTripRowId: StateFlow<Long?> = _activeTripRowId.asStateFlow()

    init {
        // Beim Coordinator-Startup einmal alte Placeholder-Rows aufräumen, die
        // ein Vorgänger-Process hinterlassen haben könnte (App-Crash mit-Trip,
        // Force-Stop usw.). Cascade-FK räumt auch verwaiste Waypoints mit.
        scope.launch {
            runCatching { tripDao.deleteOrphans() }
                .onFailure { Log.w(TAG, "orphan cleanup failed: ${it.message}") }
        }
        scope.launch {
            ble.connection.collect { conn ->
                when (conn) {
                    is ConnectionState.Connected -> {
                        if (recorder == null) {
                            recorder = TripRecorder(conn.address)
                            currentScooterAddress = conn.address
                            Log.i(TAG, "trip armed: addr=${conn.address}")
                        }
                    }
                    else -> finalizeAndClear()
                }
            }
        }
        scope.launch {
            ble.telemetry.filterNotNull().collect { t ->
                val now = System.currentTimeMillis()
                val r = recorder ?: return@collect
                val wasEmpty = r.isEmpty()
                r.onSample(t, now)
                // Erstes Sample? → Placeholder-Row anlegen damit LocationCollector
                // einen Foreign-Key hat. NICHT in finalizeAndClear schieben: wir
                // wollen die Row jetzt, mid-trip, nicht erst beim Disconnect.
                if (wasEmpty && _activeTripRowId.value == null) {
                    val rowId = runCatching {
                        tripDao.insert(
                            Trip(
                                startedAt = now,
                                endedAt = 0L,  // Sentinel: "in progress"
                                distanceKm = 0f,
                                maxSpeedKmh = 0f,
                                avgSpeedKmh = 0f,
                                energyWh = 0f,
                                startBatteryPct = t.batteryPercent,
                                endBatteryPct = t.batteryPercent,
                                scooterAddress = currentScooterAddress ?: "",
                            )
                        )
                    }.getOrElse {
                        Log.w(TAG, "placeholder insert failed: ${it.message}")
                        return@collect
                    }
                    _activeTripRowId.value = rowId
                    Log.i(TAG, "placeholder row created: id=$rowId")
                }
            }
        }
    }

    private suspend fun finalizeAndClear() {
        val r = recorder ?: return
        val addr = currentScooterAddress
        val placeholderId = _activeTripRowId.value
        recorder = null
        currentScooterAddress = null
        _activeTripRowId.value = null

        val trip = r.snapshot()
        val persistLocally = trip != null && trip.distanceKm >= 0.01f
        val now = System.currentTimeMillis()

        if (placeholderId == null) {
            // Nie ein Telemetrie-Sample angekommen → nichts zu finalisieren.
            return
        }

        if (!persistLocally) {
            Log.i(TAG, "trip <10m, dropping placeholder (id=$placeholderId)")
            runCatching { tripDao.delete(placeholderId) }
                .onFailure { Log.w(TAG, "placeholder delete failed: ${it.message}") }
            return
        }

        // Snapshot in die Placeholder-Row updaten (gleiche PK, FK der Waypoints
        // bleibt gültig). Wir behalten die PK = placeholderId, damit der
        // deterministische `local-<rowId>`-Trip-Identifier konsistent ist mit
        // den Live-`location`-Frames, die der LocationCollector schon mit
        // genau dieser ID rausgeschickt hat.
        val finalized = trip!!.copy(id = placeholderId)
        runCatching { tripDao.update(finalized) }
            .onFailure {
                Log.w(TAG, "trip update failed: ${it.message}")
                return
            }
        Log.i(TAG, "trip finalized (Room id=$placeholderId): ${finalized.distanceKm} km")

        val tripId = LOCAL_ID_PREFIX + placeholderId
        // Inline-Waypoints aus Room ziehen — falls live-Frames verloren
        // gingen (Streaming off, Mobilfunkloch), bekommt der Server jetzt
        // einen vollständigen Trip mit allen Locations.
        val waypoints = runCatching { tripDao.locationsForTrip(placeholderId) }
            .getOrElse { emptyList() }
            .map { it.toLocationPoint() }

        streamer.send(
            StreamMessage.TripEnd(
                tripId = tripId,
                startedAt = finalized.startedAt,
                ts = max(finalized.endedAt, now),
                distanceKm = finalized.distanceKm.toDouble(),
                energyWh = finalized.energyWh.toDouble(),
                durationS = ((finalized.endedAt - finalized.startedAt) / 1000).coerceAtLeast(0),
                maxSpeedKmh = finalized.maxSpeedKmh.toDouble(),
                avgSpeedKmh = finalized.avgSpeedKmh.toDouble(),
                scooterAddress = addr,
                locations = waypoints.ifEmpty { null },
            )
        )
        Log.i(TAG, "trip_end streamed: id=$tripId (waypoints=${waypoints.size})")
    }

    internal companion object {
        const val TAG = "TripCoordinator"
        const val LOCAL_ID_PREFIX = "local-"
    }
}

/** Helper für die Inline-Variante im trip_end. */
internal fun TripLocation.toLocationPoint(): StreamMessage.LocationPoint =
    StreamMessage.LocationPoint(
        ts = ts,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        speedMps = speedMps,
    )
