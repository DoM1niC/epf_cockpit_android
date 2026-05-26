package eu.dns3d.epfcockpit.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Sammelt optionale GPS-Waypoints — strikt AOSP ([LocationManager.GPS_PROVIDER],
 * kein FusedLocationProvider, keine Google-Play-Services), 1-Sekunden-Intervall.
 *
 * Gating:
 *   - User hat in den Settings `trackLocation = true` gesetzt — Pflicht.
 *   - Während einer Fahrt (Trip aktiv + nicht geparkt): Sample wird persistent
 *     in Room geschrieben UND live an den Server geschickt.
 *   - Im Idle (kein Trip oder geparkt): nur wenn zusätzlich `trackLocationIdle
 *     = true`; Sample wird ausschließlich live geschickt (Streaming-Snapshot
 *     für die Dashboard-Karte), kein Room-Insert.
 *
 * Sobald `trackLocation` aus ist oder im Idle ohne Idle-Toggle, wird der
 * Listener wieder abgemeldet — kein Akku-Drain.
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

    /** Row-ID des aktiven Trips; null bedeutet "kein Trip läuft" (Idle-Modus). */
    @Volatile private var currentTripRowId: Long? = null

    /** Letzter Gate-Wert — gebraucht damit der Provider-Change-Receiver weiss
     *  ob er nach einem Disable→Enable des GPS-Providers wieder starten soll. */
    @Volatile private var lastShouldRun: Boolean = false

    private val providerChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
            val gpsOn = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            val netOn = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
            val anyOn = gpsOn || netOn
            Log.i(TAG, "providers changed: gps=$gpsOn net=$netOn listening=$listening shouldRun=$lastShouldRun")
            if (!anyOn && listening) stop()
            else if (anyOn && lastShouldRun && !listening) start()
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // currentTripRowId darf null sein — im Idle senden wir nur live.
            scope.launch { handleLocation(currentTripRowId, loc) }
        }

        // Pre-API-29-Callbacks — auf API 31+ werden sie nicht aufgerufen, aber
        // das Interface fordert sie noch (default-impl gibt es erst ab 30).
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Pre-API-29 only")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        Log.i(TAG, "instance created")
        // Wenn der User GPS in den Android-Settings nachtraeglich einschaltet,
        // probieren wir den Listener automatisch erneut zu starten. Ohne den
        // Receiver bliebe shouldRun=true ewig ohne Wirkung weil distinctUntil-
        // Changed weitere identische Gate-Events supprimiert.
        runCatching {
            context.registerReceiver(
                providerChangeReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            )
        }.onFailure { Log.w(TAG, "registerReceiver failed: ${it.message}") }
        scope.launch {
            // Vier Quellen: trackLocation, trackLocationIdle, aktive Trip-Row-ID,
            // parking-Flag. distinctUntilChanged auf das errechnete Gate-Tupel,
            // sodass wir Start/Stop nur fahren wenn sich was Effektives ändert.
            combine(
                settings.flow,
                tripCoordinator.activeTripRowId,
                ble.telemetry,
            ) { snap, tripRowId, tele ->
                val parked = tele?.parking == true
                val tripActive = tripRowId != null && !parked
                val shouldRun = snap.trackLocation && (tripActive || snap.trackLocationIdle)
                // Im Idle-Modus (kein Trip, oder geparkt) bleibt die Row-ID
                // null — handleLocation überspringt dann den Room-Insert.
                GateState(
                    shouldRun = shouldRun,
                    tripRowId = if (tripActive) tripRowId else null,
                    track = snap.trackLocation,
                    idle = snap.trackLocationIdle,
                    tripActive = tripActive,
                )
            }
                .distinctUntilChanged()
                .collect { g ->
                    Log.i(TAG, "gate: shouldRun=${g.shouldRun} track=${g.track} idle=${g.idle} tripActive=${g.tripActive} tripRowId=${g.tripRowId}")
                    lastShouldRun = g.shouldRun
                    currentTripRowId = g.tripRowId
                    if (g.shouldRun) start() else stop()
                }
        }
    }

    private data class GateState(
        val shouldRun: Boolean,
        val tripRowId: Long?,
        val track: Boolean,
        val idle: Boolean,
        val tripActive: Boolean,
    )

    @SuppressLint("MissingPermission")  // wir checken explizit unten
    private fun start() {
        if (listening) return
        if (!hasFinePermission()) {
            Log.w(TAG, "skip start: ACCESS_FINE_LOCATION not granted")
            return
        }
        val gpsOn = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        // NETWORK_PROVIDER ist auf Geraeten ohne Google-Dienste oft nicht
        // verfuegbar (kein /com.google.android.location-Service). Wir probieren
        // ihn trotzdem — funktioniert wenn der Hersteller microG oder einen
        // eigenen Network-Location-Provider beilegt. Sonst nur GPS.
        val netOn = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (!gpsOn && !netOn) {
            Log.w(TAG, "skip start: no location provider enabled (gps=$gpsOn net=$netOn)")
            return
        }
        var registered = false
        if (gpsOn) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M,
                    listener, Looper.getMainLooper(),
                )
                registered = true
                Log.i(TAG, "GPS provider listener started (interval=${INTERVAL_MS}ms)")
            } catch (t: Throwable) {
                Log.w(TAG, "GPS requestLocationUpdates failed: ${t.message}")
            }
        }
        if (netOn) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M,
                    listener, Looper.getMainLooper(),
                )
                registered = true
                Log.i(TAG, "NETWORK provider listener started (interval=${INTERVAL_MS}ms)")
            } catch (t: Throwable) {
                Log.w(TAG, "NETWORK requestLocationUpdates failed: ${t.message}")
            }
        }
        listening = registered
    }

    private fun stop() {
        if (!listening) return
        try {
            locationManager.removeUpdates(listener)
            Log.i(TAG, "location updates stopped")
        } catch (t: Throwable) {
            Log.w(TAG, "removeUpdates failed: ${t.message}")
        } finally {
            listening = false
        }
    }

    private suspend fun handleLocation(tripRowId: Long?, loc: Location) {
        val ts = if (loc.time > 0) loc.time else System.currentTimeMillis()
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val speed = if (loc.hasSpeed()) loc.speed else null
        Log.d(TAG, "fix lat=${loc.latitude} lon=${loc.longitude} acc=$accuracy tripRowId=$tripRowId")

        // 1) Persistieren NUR während eines aktiven Trips. Im Idle wäre der
        //    Sample heimatlos (keine Trip-Row, kein Backfill-Mechanismus).
        if (tripRowId != null) {
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
        }

        // 2) Live an den Server pushen — Streamer ist no-op wenn nicht
        //    connected. tripId = null signalisiert dem Dashboard "Idle-Fix"
        //    (Marker bewegen, aber keinen Trail anhängen).
        val tripId = tripRowId?.let { TripCoordinator.LOCAL_ID_PREFIX + it }
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
        const val INTERVAL_MS: Long = 1_000L
        const val MIN_DISTANCE_M: Float = 0f
    }
}
