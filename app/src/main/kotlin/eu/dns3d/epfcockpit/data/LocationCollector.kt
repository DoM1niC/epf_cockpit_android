package eu.dns3d.epfcockpit.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sammelt optionale GPS-Waypoints während einer Fahrt — strikt AOSP
 * ([LocationManager.GPS_PROVIDER], kein FusedLocationProvider, keine
 * Google-Play-Services), 5-Sekunden-Intervall.
 *
 * Gating: GPS-Updates laufen NUR wenn alle drei Bedingungen erfüllt sind:
 *   - User hat in den Settings `trackLocation = true` gesetzt
 *   - Es läuft gerade ein Trip ([TripCoordinator.activeTripRowId] != null)
 *   - Der Roller ist nicht geparkt (`telemetry.parking == false`)
 *
 * Sobald eine der Bedingungen wegfällt, wird der Listener wieder abgemeldet.
 * Damit kein Akku-Drain wenn die App offen ist aber niemand fährt.
 *
 * Jeder Sample wird in zwei Wegen versendet:
 *   - persistent: Room (`TripDao.insertLocation`) mit der aktiven Trip-Row-ID
 *   - live: WebSocket (`TelemetryStreamer.sendLocation`) — der Streamer ist
 *     no-op wenn der Socket nicht gerade connected ist, also keine Pufferung
 *     nötig (das macht der Backfill via `trip_end.locations`).
 *
 * Wird vom [eu.dns3d.epfcockpit.service.EpfConnectionService] eagerly
 * injected damit der Listener auch ohne offene UI scharf geschaltet ist.
 */
@Singleton
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val ble: BleRepository,
    private val tripCoordinator: TripCoordinator,
    private val tripDao: TripDao,
    private val streamer: TelemetryStreamer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /** Wird auf true gesetzt sobald wir requestLocationUpdates aufgerufen haben. */
    @Volatile private var listening: Boolean = false

    /** Die Row-ID des Trips, an den die nächsten Locations gehängt werden. */
    @Volatile private var currentTripRowId: Long? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val rowId = currentTripRowId ?: return
            scope.launch { handleLocation(rowId, loc) }
        }

        // Pre-API-29-Callbacks — auf API 31+ werden sie nicht aufgerufen, aber
        // das Interface fordert sie noch (default-impl gibt es erst ab 30).
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Pre-API-29 only")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        scope.launch {
            // Drei Quellen: Settings.trackLocation, aktive Trip-Row-ID, parking-Flag.
            // distinctUntilChanged + Triple-Reduce → wir starten/stoppen nur
            // wenn sich der effektive Gate-Zustand wirklich geändert hat.
            combine(
                settings.flow,
                tripCoordinator.activeTripRowId,
                ble.telemetry,
            ) { snap, tripRowId, tele ->
                val parked = tele?.parking == true
                Triple(snap.trackLocation, tripRowId, parked)
            }
                .distinctUntilChanged()
                .collect { (track, tripRowId, parked) ->
                    val shouldRun = track && tripRowId != null && !parked
                    if (shouldRun) {
                        currentTripRowId = tripRowId
                        start()
                    } else {
                        currentTripRowId = null
                        stop()
                    }
                }
        }
    }

    @SuppressLint("MissingPermission")  // wir checken explizit unten
    private fun start() {
        if (listening) return
        if (!hasFinePermission()) {
            Log.w(TAG, "skip start: ACCESS_FINE_LOCATION not granted")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "skip start: GPS provider disabled by user")
            return
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            listening = true
            Log.i(TAG, "GPS updates started (interval=${INTERVAL_MS}ms)")
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates failed: ${t.message}")
        }
    }

    private fun stop() {
        if (!listening) return
        try {
            locationManager.removeUpdates(listener)
            Log.i(TAG, "GPS updates stopped")
        } catch (t: Throwable) {
            Log.w(TAG, "removeUpdates failed: ${t.message}")
        } finally {
            listening = false
        }
    }

    private suspend fun handleLocation(tripRowId: Long, loc: Location) {
        val ts = if (loc.time > 0) loc.time else System.currentTimeMillis()
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val speed = if (loc.hasSpeed()) loc.speed else null

        // 1) Persistieren — auch wenn der Server gerade nicht erreichbar ist,
        //    fischt der TripSyncer / das trip_end-Backfill diese Waypoints
        //    später ab.
        runCatching {
            tripDao.insertLocation(
                TripLocation(
                    tripRowId = tripRowId,
                    ts = ts,
                    lat = loc.latitude,
                    lon = loc.longitude,
                    accuracyM = accuracy,
                    speedMps = speed,
                )
            )
        }.onFailure { Log.w(TAG, "insertLocation failed: ${it.message}") }

        // 2) Live an den Server pushen — Streamer ist no-op wenn nicht
        //    connected, also keine extra Logik nötig.
        val tripId = TripCoordinator.LOCAL_ID_PREFIX + tripRowId
        streamer.sendLocation(
            tripId = tripId,
            ts = ts,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = accuracy,
            speedMps = speed,
        )
    }

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "LocationCollector"
        const val INTERVAL_MS: Long = 5_000L
        const val MIN_DISTANCE_M: Float = 0f
    }
}
