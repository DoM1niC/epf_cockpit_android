package eu.dns3d.epfcockpit.data

import android.util.Log
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.ReconnectStatus
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.streaming.StreamMessage
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recordet Fahrten unabhängig von der UI-Lifecycle.
 *
 * Das Recording hängt an [BleRepository.connection]:
 *   - Connected → [TripRecorder] scharf schalten (oder, innerhalb der
 *     Gnadenfrist nach einem Funkabriss, den laufenden weiterführen).
 *   - Telemetry-Frame → `recorder.onSample`.
 *     Beim ersten Sample wird eine Placeholder-Row in Room angelegt
 *     (endedAt=0) — sonst hat der [LocationCollector] keine Trip-Row-ID für
 *     die Foreign-Key-Verknüpfung der GPS-Waypoints.
 *     Beim ersten Sample IN BEWEGUNG beginnt die Fahrt: `trip_start` geht an
 *     den Server, der ab dann Telemetrie und Live-Waypoints zuordnet.
 *   - Verbindung weg:
 *       * bewusst getrennt → sofort finalisieren
 *       * Abriss → [LINK_LOSS_GRACE_MS] warten, solange eine Reconnect-Episode
 *         läuft. Kommt der Link zurück, bleibt es EINE Fahrt.
 *   - Finalisieren:
 *       * nie gerollt / <10 m → Placeholder-Row löschen (Cascade entfernt die
 *         Waypoints), ggf. `trip_cancel` an den Server
 *       * sonst Row mit den Endwerten überschreiben und `trip_end` mit allen
 *         Waypoints streamen. Bestätigt der Server nicht (offline), schickt
 *         der [TripSyncer] die Fahrt beim nächsten Connect nach.
 *
 * Alle Zustandswechsel laufen unter [mutex]: Connection- und Telemetrie-
 * Collector laufen auf verschiedenen Threads, und ein Disconnect mitten im
 * Placeholder-Insert hinterließe sonst eine verwaiste Row.
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
    private val mutex = Mutex()

    // Alles ab hier nur unter [mutex] anfassen.
    private var recorder: TripRecorder? = null
    private var currentScooterAddress: String? = null
    private var graceJob: Job? = null

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
        scope.launch {
            // Zuerst alte Placeholder-Rows aufräumen, die ein Vorgänger-Process
            // hinterlassen haben könnte (App-Crash mit Trip, Force-Stop usw.).
            // Cascade-FK räumt auch verwaiste Waypoints mit. Erst danach die
            // Collector starten, damit nie eine frische Row mit weggeräumt wird.
            runCatching { tripDao.deleteOrphans() }
                .onFailure { Log.w(TAG, "orphan cleanup failed: ${it.message}") }

            launch {
                ble.connection.collect { conn -> mutex.withLock { onConnection(conn) } }
            }
            launch {
                ble.telemetry.filterNotNull().collect { t -> mutex.withLock { onTelemetry(t) } }
            }
            // Nach jedem (Re-)Connect des WebSockets die laufende Fahrt erneut
            // ankündigen — der Server kann neu gestartet sein, oder das erste
            // trip_start fiel ins Funkloch. Serverseitig idempotent.
            launch {
                streamer.status
                    .filter { it == TelemetryStreamer.Status.Connected }
                    .collect { mutex.withLock { announceActiveTrip() } }
            }
        }
    }

    private suspend fun onConnection(conn: ConnectionState) {
        if (conn is ConnectionState.Connected) {
            graceJob?.cancel()
            graceJob = null
            // Anderer Roller innerhalb der Gnadenfrist → das ist eine neue Fahrt.
            if (recorder != null && currentScooterAddress != conn.address) finalizeAndClear()
            if (recorder == null) {
                recorder = TripRecorder(conn.address)
                currentScooterAddress = conn.address
                Log.i(TAG, "trip armed: addr=${conn.address}")
            }
            return
        }
        if (recorder == null) return
        if (ble.userDisconnected) {
            graceJob?.cancel()
            graceJob = null
            finalizeAndClear()
            return
        }
        if (graceJob == null) {
            Log.i(TAG, "link lost — Fahrt bleibt bis zu ${LINK_LOSS_GRACE_MS / 1000}s offen")
            graceJob = scope.launch { awaitGraceThenFinalize() }
        }
    }

    /**
     * Gnadenfrist nach einem Abriss. Endet vorzeitig, sobald keine
     * Reconnect-Episode (mehr) läuft — dann kommt der Link nicht von selbst
     * zurück, und ohne Episode hält auch kein Foreground-Service den Prozess
     * am Leben. Der Status wird erst nach [EPISODE_SETTLE_MS] geprüft: die
     * Episode startet einen Wimpernschlag NACH dem Zustandswechsel.
     */
    private suspend fun awaitGraceThenFinalize() {
        val self = currentCoroutineContext()[Job]
        delay(EPISODE_SETTLE_MS)
        withTimeoutOrNull(LINK_LOSS_GRACE_MS - EPISODE_SETTLE_MS) {
            ble.reconnectStatus.first { it is ReconnectStatus.Idle }
        }
        mutex.withLock {
            if (graceJob !== self) return  // inzwischen überholt (Reconnect / Trennen)
            graceJob = null
            // Eine erfolgreiche Episode endet ebenfalls mit Idle — dann steht der Link.
            if (ble.connection.value is ConnectionState.Connected) return
            finalizeAndClear()
        }
    }

    private suspend fun onTelemetry(t: Telemetry) {
        val r = recorder ?: return
        val now = System.currentTimeMillis()
        val wasStarted = r.hasStarted
        r.onSample(t, now)
        // Erstes Sample? → Placeholder-Row anlegen damit LocationCollector
        // einen Foreign-Key hat. Bewusst schon im Stand: so hat das GPS beim
        // Losfahren bereits einen Fix.
        if (_activeTripRowId.value == null) {
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
                return
            }
            _activeTripRowId.value = rowId
            Log.i(TAG, "placeholder row created: id=$rowId")
        }
        if (!wasStarted && r.hasStarted) {
            Log.i(TAG, "trip started (rollt): id=${_activeTripRowId.value}")
            announceActiveTrip()
        }
    }

    /** `trip_start` für die laufende Fahrt (no-op solange nicht gerollt wurde). */
    private fun announceActiveTrip() {
        val r = recorder ?: return
        val rowId = _activeTripRowId.value ?: return
        if (!r.hasStarted) return
        streamer.send(StreamMessage.TripStart(tripId = LOCAL_ID_PREFIX + rowId, ts = r.startedAtMillis))
    }

    private suspend fun finalizeAndClear() {
        val r = recorder ?: return
        val placeholderId = _activeTripRowId.value
        recorder = null
        currentScooterAddress = null
        _activeTripRowId.value = null

        // Nie ein Telemetrie-Sample angekommen → nichts zu finalisieren.
        if (placeholderId == null) return
        val tripId = LOCAL_ID_PREFIX + placeholderId

        val trip = r.snapshot()
        if (trip == null || trip.distanceKm < MIN_DISTANCE_KM) {
            Log.i(TAG, "trip <10m, dropping placeholder (id=$placeholderId)")
            runCatching { tripDao.delete(placeholderId) }
                .onFailure { Log.w(TAG, "placeholder delete failed: ${it.message}") }
            // Der Server kennt die Fahrt nur, wenn trip_start rausging.
            if (r.hasStarted) streamer.send(StreamMessage.TripCancel(tripId))
            return
        }

        // Snapshot in die Placeholder-Row updaten (gleiche PK, FK der Waypoints
        // bleibt gültig). Wir behalten die PK = placeholderId, damit der
        // deterministische `local-<rowId>`-Trip-Identifier konsistent ist mit
        // den Live-`location`-Frames, die der LocationCollector schon mit
        // genau dieser ID rausgeschickt hat.
        val finalized = trip.copy(id = placeholderId)
        runCatching { tripDao.update(finalized) }
            .onFailure {
                Log.w(TAG, "trip update failed: ${it.message}")
                return
            }
        // Waypoints aus der Standzeit vor dem Losfahren / nach dem Abstellen
        // gehören nicht auf die Route.
        runCatching {
            tripDao.trimLocations(
                placeholderId,
                from = finalized.startedAt - LOCATION_MARGIN_MS,
                to = finalized.endedAt + LOCATION_MARGIN_MS,
            )
        }.onFailure { Log.w(TAG, "trimLocations failed: ${it.message}") }
        Log.i(TAG, "trip finalized (Room id=$placeholderId): ${finalized.distanceKm} km")

        // Inline-Waypoints aus Room ziehen — falls live-Frames verloren
        // gingen (Streaming off, Mobilfunkloch), bekommt der Server jetzt
        // einen vollständigen Trip mit allen Locations.
        val waypoints = runCatching { tripDao.locationsForTrip(placeholderId) }
            .getOrElse { emptyList() }
        streamer.send(finalized.toTripEnd(waypoints))
        Log.i(TAG, "trip_end streamed: id=$tripId (waypoints=${waypoints.size})")
    }

    internal companion object {
        const val TAG = "TripCoordinator"
        const val LOCAL_ID_PREFIX = "local-"
        const val MIN_DISTANCE_KM = 0.01f
        /** So lange bleibt eine Fahrt nach einem Funkabriss offen. */
        const val LINK_LOSS_GRACE_MS = 90_000L
        const val EPISODE_SETTLE_MS = 2_000L
        const val LOCATION_MARGIN_MS = 10_000L
    }
}

/** `trip_end` für eine abgeschlossene Fahrt — live und für den Backfill identisch. */
internal fun Trip.toTripEnd(waypoints: List<TripLocation>): StreamMessage.TripEnd =
    StreamMessage.TripEnd(
        tripId = TripCoordinator.LOCAL_ID_PREFIX + id,
        startedAt = startedAt,
        ts = endedAt,
        distanceKm = distanceKm.toDouble(),
        energyWh = energyWh.toDouble(),
        durationS = ((endedAt - startedAt) / 1000).coerceAtLeast(0),
        maxSpeedKmh = maxSpeedKmh.toDouble(),
        avgSpeedKmh = avgSpeedKmh.toDouble(),
        scooterAddress = scooterAddress,
        locations = waypoints.map { it.toLocationPoint() }.ifEmpty { null },
    )

/** Helper für die Inline-Variante im trip_end. */
internal fun TripLocation.toLocationPoint(): StreamMessage.LocationPoint =
    StreamMessage.LocationPoint(
        ts = ts,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        speedMps = speedMps,
    )
