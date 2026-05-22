package eu.dns3d.epfcockpit.data

import android.util.Log
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recordet Fahrten unabhängig von der UI-Lifecycle. Der frühere Code im
 * DashboardViewModel hat das Recording finalisiert sobald die VM zerstört
 * wurde — d.h. sobald man zu Settings/History navigiert hat oder der
 * Screen aus ging.
 *
 * Hier hängt das Recording an [BleRepository.connection]:
 *   - Connected → neuer TripRecorder pro Verbindung
 *   - Telemetry-Frame → `recorder.onSample`
 *   - Disconnected → finalize, in Room persistieren, recorder = null
 *
 * Wird als @Singleton von [eu.dns3d.epfcockpit.MainActivity] eagerly
 * injected, damit das Recording schon ab App-Start aktiv ist.
 */
@Singleton
class TripCoordinator @Inject constructor(
    private val ble: BleRepository,
    private val tripDao: TripDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: TripRecorder? = null

    init {
        scope.launch {
            ble.connection.collect { conn ->
                when (conn) {
                    is ConnectionState.Connected -> {
                        if (recorder == null) {
                            recorder = TripRecorder(conn.address)
                            Log.i(TAG, "trip recording armed for ${conn.address}")
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
        recorder = null
        val trip = r.finalize() ?: return
        runCatching { tripDao.insert(trip) }
            .onSuccess { Log.i(TAG, "trip persisted: ${trip.distanceKm} km") }
            .onFailure { Log.w(TAG, "trip persist failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "TripCoordinator"
    }
}
