package eu.dns3d.epfcockpit.data

import android.util.Log
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.streaming.StreamMessage
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 *   - Disconnected → finalize:
 *       * lokal: in Room nur wenn ≥10 m (sonst Geister-Trip)
 *       * remote: trip_end-Event an [TelemetryStreamer] mit allen Stats,
 *         self-contained (Server macht Insert-or-Update)
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

    init {
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
                recorder?.onSample(t, System.currentTimeMillis())
            }
        }
    }

    private suspend fun finalizeAndClear() {
        val r = recorder ?: return
        val addr = currentScooterAddress
        recorder = null
        currentScooterAddress = null

        val trip = r.snapshot() ?: return  // null nur wenn nie gesamplet
        val persistLocally = trip.distanceKm >= 0.01f
        val now = System.currentTimeMillis()

        if (!persistLocally) {
            Log.i(TAG, "trip <10m, not persisted locally (distance=${trip.distanceKm})")
            return
        }

        // Erst lokal persistieren (gibt Room-PK zurück), dann den deterministischen
        // server-Trip-ID daraus bauen. Damit kann [TripSyncer] dieselben Trips
        // später idempotent re-pushen ohne Doubletten.
        val rowId = runCatching { tripDao.insert(trip) }
            .getOrElse {
                Log.w(TAG, "trip persist failed: ${it.message}")
                return
            }
        Log.i(TAG, "trip persisted (Room id=$rowId): ${trip.distanceKm} km")

        val tripId = LOCAL_ID_PREFIX + rowId
        streamer.send(
            StreamMessage.TripEnd(
                tripId = tripId,
                startedAt = trip.startedAt,
                ts = max(trip.endedAt, now),
                distanceKm = trip.distanceKm.toDouble(),
                energyWh = trip.energyWh.toDouble(),
                durationS = ((trip.endedAt - trip.startedAt) / 1000).coerceAtLeast(0),
                maxSpeedKmh = trip.maxSpeedKmh.toDouble(),
                avgSpeedKmh = trip.avgSpeedKmh.toDouble(),
                scooterAddress = addr,
            )
        )
        Log.i(TAG, "trip_end streamed: id=$tripId")
    }

    internal companion object {
        const val TAG = "TripCoordinator"
        const val LOCAL_ID_PREFIX = "local-"
    }
}
