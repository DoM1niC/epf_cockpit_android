package eu.dns3d.epfcockpit.streaming

import android.util.Log
import eu.dns3d.epfcockpit.BuildConfig
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes telemetry, connection state and trip lifecycle events from
 * [BleRepository] to a Node-Server via WebSocket, see [docs/STREAMING.md].
 *
 * Single source of truth lives in [SettingsStore] (`streamingUrl` +
 * `streamingEnabled`). When either changes we tear the socket down and
 * (re)connect with exponential backoff.
 */
@Singleton
class TelemetryStreamer @Inject constructor(
    private val ble: BleRepository,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Schützt [socket]. Die Listener-Callbacks laufen auf OkHttp-Threads und
     * dürfen nur für den AKTUELLEN Socket wirken: das verspätete onClosed eines
     * abgelösten Sockets hat sonst den frischen auf null gesetzt und einen
     * unnötigen Reconnect ausgelöst.
     */
    private val socketLock = Any()
    private var socket: WebSocket? = null
    private var socketJob: Job? = null

    // Nur vom Telemetrie-Collector angefasst.
    private var lastSentTelemetryTs: Long = 0L
    private var lastSentFlags: Triple<Boolean?, Boolean?, Boolean>? = null

    private val _tripAcks = MutableSharedFlow<String>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Trip-IDs, deren `trip_end` der Server verarbeitet und quittiert hat. */
    val tripAcks: SharedFlow<String> = _tripAcks.asSharedFlow()
    // Vom User gesetzter Anzeigename — wird im hello/state-Frame als scooterName
    // mitgeschickt. Null = Dashboard zeigt "E-Scooter" als Fallback. Cached
    // statt suspend-Lookup, weil sendState/sendHello aus dem Listener heraus
    // non-suspend laufen.
    @Volatile private var cachedFriendlyName: String? = null

    private val _status = MutableStateFlow(Status.Disabled)
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Status { Disabled, Connecting, Connected, Failed }

    init {
        // Watch settings: on every (url, enabled, token)-change start/stop the socket.
        // Token changes also require a reconnect because we set it as a header on
        // upgrade — there's no in-band way to swap credentials on an open socket.
        scope.launch {
            settings.flow
                .map { Triple(it.streamingUrl, it.streamingEnabled, it.streamingToken) }
                .distinctUntilChanged()
                .collect { (url, enabled, token) ->
                    cancelSocket()
                    if (enabled && !url.isNullOrBlank()) {
                        socketJob = launchSocketLoop(url, token)
                    } else {
                        _status.value = Status.Disabled
                    }
                }
        }
        // Friendly name separat cachen — wird live im hello/state mitgesendet.
        // Aenderungen pushen wir aktiv via sendState damit das Dashboard das
        // neue Label sofort sieht (ohne Reconnect-Bounce).
        scope.launch {
            settings.flow
                .map { it.scooterFriendlyName }
                .distinctUntilChanged()
                .collect { name ->
                    val changed = cachedFriendlyName != name
                    cachedFriendlyName = name
                    if (changed) sendState(ble.connection.value)
                }
        }
        // Forward connection-state changes to the server (when socket is open).
        scope.launch {
            ble.connection.collect { c -> sendState(c) }
        }
        // Forward telemetry: ~5 Hz in Fahrt, ~1 Hz im Stand. Der Server
        // speichert jedes Frame — im Stand (Laden, Garage) wären 5 Hz reine
        // Datenmenge. Schaltvorgänge (Park/Licht/Modus) gehen sofort raus.
        scope.launch {
            ble.telemetry.filterNotNull().collect { t ->
                val now = System.currentTimeMillis()
                val flags = Triple(ble.parkingDisplay.value, ble.lightOn.value, ble.rideModeStand)
                val minGap = if (t.speedKmh >= MOVING_SPEED_KMH) MOVING_INTERVAL_MS else IDLE_INTERVAL_MS
                if (flags == lastSentFlags && now - lastSentTelemetryTs < minGap) return@collect
                lastSentTelemetryTs = now
                lastSentFlags = flags
                sendTelemetry(t, now)
            }
        }
    }

    private fun launchSocketLoop(url: String, token: String?): Job = scope.launch {
        var backoffMs = 1_000L
        while (isActive) {
            _status.value = Status.Connecting
            val ws = openSocket(url, token)
            if (ws != null) {
                backoffMs = 1_000L  // reset on successful open
                // Wait until the socket dies — the listener flips _status back to Failed.
                _status.first { it != Status.Connected }
            } else {
                _status.value = Status.Failed
            }
            if (!isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Öffnet einen Socket und wartet kurz auf onOpen. Null bei Fehlschlag. */
    private suspend fun openSocket(url: String, token: String?): WebSocket? {
        val ws = try {
            // Prefer Authorization-Header over ?token=…: stays out of nginx
            // access.log $request, and out of OkHttp's URL toString on error.
            val builder = Request.Builder().url(url)
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            // Unter dem Lock, damit onOpen den Socket garantiert schon kennt.
            synchronized(socketLock) {
                client.newWebSocket(builder.build(), listener).also { socket = it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openSocket failed: ${t.message}")
            return null
        }
        val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) { _status.first { it != Status.Connecting } }
        if (opened == Status.Connected) return ws
        // Nicht (rechtzeitig) offen: den Versuch wirklich abbrechen — sonst
        // könnte er später doch noch aufgehen, parallel zum nächsten.
        synchronized(socketLock) { if (socket === ws) socket = null }
        ws.cancel()
        return null
    }

    private fun cancelSocket() {
        socketJob?.cancel()
        socketJob = null
        val old = synchronized(socketLock) { socket.also { socket = null } }
        try { old?.close(1000, "settings-changed") } catch (_: Throwable) {}
    }

    private fun isCurrent(webSocket: WebSocket): Boolean =
        synchronized(socketLock) { socket === webSocket }

    /** Socket als tot markieren — nur wenn es noch der aktuelle ist. */
    private fun dropIfCurrent(webSocket: WebSocket): Boolean = synchronized(socketLock) {
        if (socket !== webSocket) return false
        socket = null
        true
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) {
                webSocket.cancel()
                return
            }
            // Send hello with whatever we currently know about the scooter.
            // scooterName = vom User vergebener Anzeigename (cached), Address
            // bleibt die echte MAC fuer Server-side-Logging/Trip-Tagging.
            // hello VOR dem Status-Wechsel: wer auf Connected wartet
            // (TripCoordinator, TripSyncer), sendet garantiert erst danach.
            val conn = ble.connection.value
            sendRaw(
                StreamMessage.Hello(
                    scooterName = cachedFriendlyName,
                    scooterAddress = (conn as? ConnectionState.Connected)?.address,
                    authenticated = (conn as? ConnectionState.Connected)?.authenticated == true,
                    app = "epf_cockpit_android",
                    appVersion = BuildConfig.VERSION_NAME,
                )
            )
            // Send last known state + telemetry so the browser doesn't have to wait.
            sendState(conn)
            ble.telemetry.value?.let { sendTelemetry(it, System.currentTimeMillis()) }
            synchronized(socketLock) {
                if (socket === webSocket) _status.value = Status.Connected
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Einzige Server→App-Nachricht bisher: {"type":"trip_ack","tripId":"local-12"}
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "trip_ack") {
                    obj["tripId"]?.jsonPrimitive?.content?.let { _tripAcks.tryEmit(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ws onMessage: unlesbar (${t.message})")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!dropIfCurrent(webSocket)) return
            Log.w(TAG, "ws onFailure: ${t.message}")
            _status.value = Status.Failed
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!dropIfCurrent(webSocket)) return
            _status.value = Status.Failed
        }
    }

    private fun sendTelemetry(t: Telemetry, nowMillis: Long) {
        sendRaw(
            StreamMessage.Telemetry(
                ts = nowMillis,
                speedKmh = t.speedKmh.toDouble(),
                batteryPercent = t.batteryPercent,
                voltageV = t.voltage.toDouble(),
                currentA = t.currentAmps.toDouble(),
                powerW = t.watts.toDouble(),
                controllerTempC = t.controllerTempC.toDouble(),
                tripKm = t.tripKm.toDouble(),
                totalKm = t.totalKm.toDouble(),
                // Park + Mode wie in der App: das Telemetrie-Bit 5 ist mehrdeutig
                // (geparkt ODER Stand-Mode) — daher kam im Web „Parking: an“ bei
                // entsperrtem Roller im Stand-Mode.
                parking = ble.parkingDisplay.value ?: t.parking,
                rideModeStand = ble.rideModeStand,
                lightOn = ble.lightOn.value,
                motorTempC = t.motorTempC.toDouble(),
            )
        )
    }

    private fun sendState(c: ConnectionState) {
        val connected = c is ConnectionState.Connected
        val authenticated = (c as? ConnectionState.Connected)?.authenticated == true
        sendRaw(StreamMessage.State(
            connected = connected,
            authenticated = authenticated,
            scooterName = cachedFriendlyName,
        ))
    }

    private inline fun <reified T : StreamMessage> sendRaw(msg: T) {
        val s = synchronized(socketLock) { socket } ?: return
        try {
            s.send(json.encodeToString<StreamMessage>(msg))
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.message}")
        }
    }

    /**
     * Öffentlicher Fire-and-Forget-Send für externe Caller (z.B. [TripCoordinator]
     * für `trip_start`/`trip_end`). No-op wenn der Socket gerade nicht
     * verbunden ist — der Server würde die Frame ohnehin nie bekommen, und
     * wir wollen keine Buffer/Replay-Komplexität.
     */
    fun send(msg: StreamMessage) {
        sendRaw<StreamMessage>(msg)
    }

    /**
     * Live-Waypoint vom [eu.dns3d.epfcockpit.data.LocationCollector]. Identisch
     * zu [send] — eigene Methode, damit die Call-Sites lesbar bleiben und der
     * Streamer später bei Bedarf ein Throttling/Burst-Filtering einfügen kann
     * ohne dass die Caller anpassen müssen.
     */
    fun sendLocation(
        tripId: String?,
        ts: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float?,
        speedMps: Float?,
    ) {
        sendRaw<StreamMessage>(
            StreamMessage.Location(
                tripId = tripId,
                ts = ts,
                lat = lat,
                lon = lon,
                accuracyM = accuracyM,
                speedMps = speedMps,
            )
        )
    }

    private companion object {
        const val TAG = "TelemetryStreamer"
        const val OPEN_TIMEOUT_MS = 5_000L
        const val MOVING_SPEED_KMH = 1f
        const val MOVING_INTERVAL_MS = 200L
        const val IDLE_INTERVAL_MS = 1_000L
    }
}
