package eu.dns3d.epfcockpit.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import javax.inject.Inject
import javax.inject.Singleton

/** Stable summary of a scooter we have seen during scanning. */
data class DiscoveredScooter(
    val address: String,
    val name: String?,
    val rssi: Int,
    val likelyScooter: Boolean = false,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val address: String, val authenticated: Boolean) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

@Singleton
class BleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val manager = ScooterManager(context)
    private var heartbeatJob: Job? = null

    /**
     * Sticky flag for the current process: true once the user pressed the
     * Trennen-button in the Dashboard. Suppresses auto-reconnect until the
     * app is killed and started again, so we don't fight the user when they
     * just want to scan for another scooter / take a break.
     */
    @Volatile var userDisconnected: Boolean = false
        private set

    /**
     * Ist die App gerade im Vordergrund? Gesetzt vom [eu.dns3d.epfcockpit.service.ForegroundController]
     * via ProcessLifecycle (Application-scoped, also auch bei Kaltstart über
     * Widget/Tile). Default false: ohne sichtbare Activity sind wir Hintergrund.
     *
     * BLE-Peripherals wie der Hobbywing-Controller sind Single-Link: solange ein
     * Central verbunden ist (oder aktiv connecten will), advertised der Roller
     * nicht und ist für andere Apps "verschwunden". Deshalb entscheidet
     * [ReconnectPolicy], ob eine Reconnect-Episode im Hintergrund aktiv sucht,
     * per Sentinel wartet (Handy gesperrt), pausiert (entsperrt + andere App
     * vorne → Handover an z.B. die Hersteller-App) oder bis zum App-Start
     * zurückgestellt wird.
     *
     * Eine bereits STEHENDE Verbindung bleibt im Hintergrund immer erhalten
     * (Ride-Telemetrie bei Screen-off).
     */
    @Volatile private var appInForeground: Boolean = false

    /**
     * Merker: Eine Episode wurde im Hintergrund beendet/zurückgestellt. Beim
     * Zurückkehren in den Vordergrund startet eine frische Episode. Entkoppelt
     * vom Connection-State, damit die Scan-Screen-eigene Auto-Connect-Logik
     * nicht doppelt getriggert wird.
     */
    @Volatile private var resumeOnForeground: Boolean = false

    /**
     * true sobald in diesem Prozess eine Session per PIN authentifiziert war
     * (bis zum manuellen Trennen). Nur solche Sessions dürfen im Hintergrund
     * wiederhergestellt werden — ein nie authentifizierter Link hätte ohnehin
     * keine Telemetrie. Bewusst nicht an den letzten State gekoppelt: reißt ein
     * Link genau während der Re-Auth ab, bleibt die Berechtigung bestehen.
     */
    @Volatile private var hadAuthSession: Boolean = false

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    // === Reconnect-Episode ===
    private val episodeLock = Any()
    private var episodeJob: Job? = null
    @Volatile private var episodeForce = false
    @Volatile private var cachedSnap: SettingsStore.Snapshot? = null
    @Volatile private var lastMovingAtElapsed = 0L
    @Volatile private var bgRestoreStreak = 0
    @Volatile private var lastBgRestoreAtElapsed = 0L

    /** Anstoß zur Neubewertung (Vorder-/Hintergrund, Display, Settings, Aktionen). */
    private val policyTick = Channel<Unit>(Channel.CONFLATED)
    private val presenceEvents = Channel<Unit>(Channel.CONFLATED)
    private val interactivity = DeviceInteractivity(context) { bluetoothStateChanged ->
        // Nach BT aus/an ist die GATT-Registrierung des Sentinels tot → neu armieren lassen.
        if (bluetoothStateChanged) presence.disarm()
        policyTick.trySend(Unit)
    }
    private val presence = ScooterPresenceWatcher(
        context,
        onPresent = { presenceEvents.trySend(Unit) },
        onDiscarded = {
            // Gedrosselt neu armieren: ein paar sofortige Versuche, danach nur noch
            // bei Ticks/Probes — kein Arm/Fail-Spin bei dauerhaftem Fehler.
            if (sentinelDiscards++ < SENTINEL_REARM_LIMIT) policyTick.trySend(Unit)
        },
    )
    @Volatile private var sentinelDiscards = 0

    private val powerManager = context.getSystemService(PowerManager::class.java)
    /** Hält die CPU für aktive Versuche im Hintergrund wach (Backoff-Delays stehen sonst im Suspend). */
    private val reconnectWake = newWakeLock("EpfCockpit:reconnect")
    /** Kurz nach einem Reconnect: Re-Auth (500 ms delay), Init-Burst, Heartbeat-Start absichern. */
    private val postConnectWake = newWakeLock("EpfCockpit:postconnect")
    /** Adaptiv: nur nachdem der Heartbeat eine CPU-Suspend-Lücke gemessen hat. */
    private val heartbeatWake = newWakeLock("EpfCockpit:heartbeat")
    @Volatile private var heartbeatStarved = false
    @Volatile private var lastHeartbeatTickElapsed = 0L
    /** Schützt heartbeatJob/initBurstJob — Start (atReplies) und Stop (connEvents) laufen auf verschiedenen Threads. */
    private val heartbeatLock = Any()
    private var initBurstJob: Job? = null

    /**
     * Anfrage an eine laufende Episode, die in genau dem Moment endet: das
     * finally startet dann sofort eine neue (sonst gingen Widget-Taps oder
     * App-Öffnen in diesem Fenster verloren). Am Schleifenanfang verbraucht.
     */
    @Volatile private var pendingRestart = false
    @Volatile private var pendingRestartForce = false

    private fun newWakeLock(tag: String): PowerManager.WakeLock? =
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply { setReferenceCounted(false) }

    private val _reconnect = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
    val reconnectStatus: StateFlow<ReconnectStatus> = _reconnect.asStateFlow()

    private val _hint = MutableStateFlow<ReconnectHint?>(null)
    /** Nicht-null wenn eine Episode im Hintergrund ohne Erfolg endete → Hinweis mit „Verbinden“. */
    val reconnectHint: StateFlow<ReconnectHint?> = _hint.asStateFlow()

    /**
     * Soll der Foreground-Service laufen? Verbunden, verbindend ODER eine
     * Reconnect-Episode läuft. Der FGS bleibt dadurch über einen Abriss hinweg
     * an: kein Cached-App-Freezer, kein FGS-Start aus dem Hintergrund
     * (ForegroundServiceStartNotAllowedException), der LOCATION-Typ bleibt.
     */
    val keepServiceAlive: StateFlow<Boolean> = combine(_connection, _reconnect) { c, r ->
        shouldKeepServiceAlive(c, r)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val telemetry: StateFlow<Telemetry?> = manager.telemetry
    val atReplies: SharedFlow<AtResponse> = manager.atReplies
    val rawFrames: SharedFlow<ByteArray> = manager.rawFrames

    /**
     * Latest voice-prompt state reported by the scooter (via `OK+TLVOICEOFF:N`).
     * `null` while we haven't asked the controller yet. The dashboard reads
     * this so the toggle reflects the real state on mount, not an optimistic
     * default that would be wrong half the time.
     */
    private val _voiceOff = MutableStateFlow<Boolean?>(null)
    val voiceOff: StateFlow<Boolean?> = _voiceOff.asStateFlow()

    /**
     * Intent-basierter Park-Zustand. Das Telemetrie-Bit 5 ist mehrdeutig
     * (geparkt vs Stand-Mode teilen sich das Bit), daher tracken wir Park als
     * Intent + blind retry gegen verlorene Frames (siehe [ParkController]).
     * [parkingDisplay] ist die einzige Quelle die jede Oberfläche anzeigt.
     */
    private val parkController = ParkController(scope, manager.telemetry, manager::writeLowFrame)
    val parkingDisplay: StateFlow<Boolean?> = parkController.parkingDisplay

    /**
     * Live-Cache der User-Max-Speed aus den Settings. Wird in JEDES `0xAF`-
     * Statusframe geschrieben (parking/mode/setMaxSpeed), damit ein Park-Toggle
     * den g3-Wert nicht zurück auf 20 km/h zwingt.
     */
    @Volatile private var cachedMaxSpeedKmh: Int = 20

    /**
     * Live-Cache des Ride-Mode (Stand vs Kick). Park-OFF muss den aktuell
     * gewählten Mode bewahren — sonst wechselt ein Park-OFF ungewollt auf Kick,
     * weil der Park-OFF-Frame (0x82) byte-identisch mit dem Kick-Frame ist.
     */
    @Volatile private var cachedRideModeStand: Boolean = true

    /** Aktuell gewählter Ride-Mode (true = Stand-Stop) — für den Web-Stream. */
    val rideModeStand: Boolean get() = cachedRideModeStand

    /**
     * Frontlicht-Zustand. Quelle ist das Telemetrie-Bit `headLightSw` (live
     * verifiziert: folgt dem echten Licht nach ~0,5 s, auch wenn der Controller
     * es beim Parken selbst abschaltet). Nach einem eigenen Kommando gilt der
     * kommandierte Wert für [LIGHT_CONFIRM_MS] optimistisch, damit der Schalter
     * nicht zurückspringt, bevor die Telemetrie nachzieht.
     * `null` = noch keine Telemetrie / getrennt.
     */
    private val _lightOn = MutableStateFlow<Boolean?>(null)
    val lightOn: StateFlow<Boolean?> = _lightOn.asStateFlow()
    @Volatile private var lightPendingUntil = 0L

    private fun commandLight(on: Boolean) {
        _lightOn.value = on
        lightPendingUntil = SystemClock.elapsedRealtime() + LIGHT_CONFIRM_MS
    }

    /** Setting „Licht beim Entsperren einschalten" (Live-Cache). */
    @Volatile private var cachedAutoLightOnUnlock: Boolean = false

    init {
        // Settings → Cache spiegeln, damit Frame-Builder synchron lesen können
        // ohne Coroutine-Hop. Erste Emission liefert auch sofort den Persistenz-
        // Default (20 km/h), bevor irgendein Frame ausgeht.
        scope.launch {
            settings.flow.collect { snap ->
                cachedMaxSpeedKmh = snap.gear3MaxKmh.coerceIn(1, 40)
                cachedRideModeStand = snap.rideModeStand
                cachedAutoLightOnUnlock = snap.autoLightOnUnlock
                val old = cachedSnap
                cachedSnap = snap
                if (old != null && (old.reconnectWhileLocked != snap.reconnectWhileLocked ||
                        old.autoConnect != snap.autoConnect)) {
                    policyTick.trySend(Unit)
                }
            }
        }
        // "Kürzlich gefahren" für die Policy: ein Abriss während der Fahrt mit
        // entsperrtem Handy (Navi vorne) ist kein Handover-Fall.
        scope.launch {
            manager.telemetry.collect { t ->
                if (t != null && t.speedKmh >= RIDING_SPEED_KMH) lastMovingAtElapsed = SystemClock.elapsedRealtime()
            }
        }
        // Licht-Zustand aus der Telemetrie übernehmen (außer während ein eigenes
        // Kommando noch auf Bestätigung wartet). Status-Wort-Änderungen loggen
        // wir auf INFO — Diagnose für Licht/Park/Lock-Bits am echten Roller.
        scope.launch {
            var lastWord = -1
            manager.telemetry.collect { t ->
                if (t == null) {
                    lastWord = -1
                    return@collect
                }
                if (t.statusWord != lastWord) {
                    Log.i(TAG, "status word: 0x%04X → 0x%04X (trip=%.1f km)".format(lastWord.coerceAtLeast(0), t.statusWord, t.tripKm))
                    lastWord = t.statusWord
                }
                val bit = t.headlightOn
                if (_lightOn.value != bit && SystemClock.elapsedRealtime() >= lightPendingUntil) {
                    Log.i(TAG, "headlight (telemetry): ${_lightOn.value} → $bit")
                    _lightOn.value = bit
                }
            }
        }
        // Ein erfolgreicher (Re-)Connect entwertet einen alten Hinweis.
        scope.launch {
            _connection.collect { c ->
                if (c is ConnectionState.Connecting || c is ConnectionState.Connected) _hint.value = null
            }
        }
        // Connection-Lifecycle vom Nordic-Manager beobachten — sonst bleibt
        // _connection auf "Connected" hängen wenn der Scooter ausgeht oder
        // außer Reichweite fährt, weil der suspend connect() längst returnt
        // hat und niemand mehr zuhört.
        scope.launch {
            manager.connEvents.collect { event ->
                when (event) {
                    is ScooterManager.ConnEvent.Disconnected -> {
                        val prev = _connection.value
                        // Heartbeat-Aussetzer VOR stopHeartbeat messen: kappt der Roller
                        // wegen eines CPU-Suspends, erreicht der Heartbeat-Loop seine
                        // eigene Messung hinter delay() nie mehr (er wird gecancelt).
                        noteHeartbeatStarvationAtDisconnect()
                        stopHeartbeat()
                        // Nie ein Pending ueber den Link-Verlust schleppen —
                        // sonst sieht der User nach dem Disconnect noch ~2.7s
                        // ein Phantom-Pending bevor die Timeouts durch sind.
                        parkController.onDisconnected()
                        _lightOn.value = null
                        when {
                            event.userInitiated || userDisconnected ->
                                _connection.value = ConnectionState.Disconnected
                            // Nordic meldet beim Ausschalten auch ohne stehenden Link —
                            // dann greift unten der else-Zweig (ScanViewModel bleibt bei Disconnected).
                            !isBluetoothEnabled() && prev is ConnectionState.Connected -> {
                                Log.i(TAG, "link lost: Bluetooth aus (reason=${event.reason})")
                                resumeOnForeground = true
                                _connection.value = ConnectionState.Failed("Bluetooth ist ausgeschaltet")
                                // Kurzes Aus/An oder Stack-Neustart: die Episode wartet (begrenzt)
                                // auf Bluetooth und verbindet danach selbst neu.
                                startEpisode(force = false, bgEligible = prev.authenticated || hadAuthSession)
                            }
                            prev is ConnectionState.Connected -> {
                                Log.i(
                                    TAG,
                                    "link lost (reason=${event.reason}, auth=${prev.authenticated}, " +
                                        "fg=$appInForeground, handsOn=${interactivity.handsOn()}) — starting reconnect episode",
                                )
                                // Failed VOR dem Episodenstart setzen: sonst kann die erste
                                // Iteration noch das alte Connected sehen und die Episode
                                // sofort als "reconnected" beenden. Den kurzen Failed+Idle-
                                // Zwischenstand absorbiert die Gnadenfrist im ForegroundController.
                                _connection.value = ConnectionState.Failed(friendlyDisconnectReason(event.reason))
                                startEpisode(force = false, bgEligible = prev.authenticated || hadAuthSession)
                            }
                            // Abriss ohne etablierten Link (z.B. während Connecting):
                            // der Besitzer des Versuchs (Episode, ScanViewModel) behandelt das.
                            else -> Log.d(TAG, "disconnect ohne etablierten Link ignoriert (prev=$prev reason=${event.reason})")
                        }
                    }
                    is ScooterManager.ConnEvent.FailedToConnect -> {
                        // Kommt asynchron — nie eine inzwischen stehende Verbindung
                        // oder eine bewusste Trennung überschreiben.
                        val c = _connection.value
                        if (c !is ConnectionState.Connected && !(c is ConnectionState.Disconnected && userDisconnected)) {
                            _connection.value = ConnectionState.Failed(friendlyDisconnectReason(event.reason))
                        }
                    }
                }
            }
        }
        // Roller schickt regelmäßig Echo-Frames `AF 01 19 00 <g1> <g2> <g3> 00…`
        // mit seinen aktuell gespeicherten Gear-Limits. Die nutzen wir als
        // Source-of-Truth für unseren Cache — verhindert dass ein veralteter
        // DataStore-Wert (z.B. nach versehentlichem Apply auf 5 km/h, oder
        // wenn der User in der EPF-App den Wert geändert hat während wir
        // disconnected waren) bei jedem Park/Mode-Toggle reingeschickt wird.
        scope.launch {
            manager.rawFrames.collect { frame ->
                // Echo-Frame-Identifikation:
                //   byte[0] = 0xAF, byte[1] = 0x01, byte[3] = 0x00
                if (frame.size >= 7
                    && frame[0] == BleConstants.FRAME_HEADER_EPF
                    && (frame[1].toInt() and 0xFF) == 0x01
                    && (frame[3].toInt() and 0xFF) == 0x00
                ) {
                    val scooterG3 = frame[6].toInt() and 0xFF
                    if (scooterG3 in 1..40 && scooterG3 != cachedMaxSpeedKmh) {
                        Log.i(TAG, "echo-sync: scooter g3=$scooterG3 (was cached=$cachedMaxSpeedKmh)")
                        cachedMaxSpeedKmh = scooterG3
                        scope.launch { settings.setGearLimits(g1 = 6, g2 = 15, g3 = scooterG3) }
                    }
                }
            }
        }
        scope.launch {
            manager.atReplies.collect { reply ->
                when (reply) {
                    AtResponse.PinOk -> {
                        hadAuthSession = true
                        _connection.update { current ->
                            if (current is ConnectionState.Connected) current.copy(authenticated = true)
                            else current
                        }
                        // Exact post-login flow captured from the official
                        // app: identify, query voice state, then 5x
                        // wake-up burst, then start the 600 ms heartbeat.
                        manager.sendAt(AtCommands.queryDevice())
                        manager.sendAt(AtCommands.queryVoiceOff())
                        startInitBurstThenHeartbeat()
                    }
                    AtResponse.PinRejected -> {
                        hadAuthSession = false
                        _connection.update { current ->
                            if (current is ConnectionState.Connected) current.copy(authenticated = false)
                            else current
                        }
                    }
                    is AtResponse.VoiceOffState -> {
                        android.util.Log.i("BleRepository", "voiceOff state from scooter: ${reply.on}")
                        _voiceOff.value = reply.on
                    }
                    else -> Unit
                }
            }
        }
    }

    fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    /**
     * Cold flow of scan results — collect to start, cancel to stop. Set
     * [scootersOnly] = false to surface every advertising BLE device with a
     * name (helps debugging when the EPF Pulse Plus advertises under an
     * unexpected name).
     */
    fun scan(scootersOnly: Boolean = true): kotlinx.coroutines.flow.Flow<DiscoveredScooter> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareBatchingIfSupported(false)
            .setLegacy(true)
            .build()

        // An empty filter list triggers Android's "location deny list" guard
        // (`Skipping ScanClient ... for location deny list`) — so we always
        // pass at least one filter. A `Builder().build()` filter is a wildcard
        // (matches every advertisement) and is enough to satisfy the guard.
        //
        // We do NOT try to filter by device name at the platform level: the
        // ePF firmware emits its name in the Scan Response, and several
        // Android BLE stacks don't match `ScanFilter.setDeviceName` against
        // scan-response names — the scooter then silently never shows up.
        // Filtering happens in Kotlin instead, on the name we read out of the
        // ScanRecord (which DOES include the scan-response name).
        val filters: List<ScanFilter> = listOf(ScanFilter.Builder().build())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitResult(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach(::emitResult)
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
            private fun emitResult(result: ScanResult) {
                // device.name braucht BLUETOOTH_CONNECT — der Scan kann auch aus
                // der Reconnect-Episode kommen, also nie im Binder-Callback crashen.
                val deviceName = try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }
                val name = deviceName ?: result.scanRecord?.deviceName
                val likely = name != null && BleConstants.ADVERTISED_NAME_PREFIXES
                    .any { name.startsWith(it, ignoreCase = true) }
                if (scootersOnly && !likely) return
                if (!scootersOnly && name == null) return
                trySend(DiscoveredScooter(result.device.address, name, result.rssi, likely))
            }
        }
        scanner.startScan(filters, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    suspend fun connect(address: String) {
        val a = adapter() ?: run {
            _connection.value = ConnectionState.Failed("Bluetooth nicht verfügbar")
            return
        }
        userDisconnected = false  // explicit connect overrides the sticky flag
        val device: BluetoothDevice = a.getRemoteDevice(address)
        _connection.value = ConnectionState.Connecting(address)
        try {
            manager.connectTo(device)
            _connection.value = ConnectionState.Connected(address, false)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation (caller stopped us, e.g. user
            // pressed Stop or navigated away). NOT a connection failure —
            // just drop back to Disconnected and let the cancellation
            // bubble up so coroutine bookkeeping stays correct.
            _connection.value = ConnectionState.Disconnected
            throw ce
        } catch (t: Throwable) {
            _connection.value = ConnectionState.Failed(friendlyError(t))
        }
    }

    private fun friendlyError(t: Throwable): String {
        val raw = (t.message ?: t::class.java.simpleName).orEmpty()
        // Nordic surfaces GATT status codes either as numeric ("status 8")
        // or as enum names ("GATT_CONN_TIMEOUT"). We catch both.
        return when {
            raw.contains("CONN_TIMEOUT", true) || raw.contains("status 8") ->
                "Scooter nicht erreichbar – ist er eingeschaltet?"
            raw.contains("TERMINATE_PEER_USER", true) || raw.contains("status 19") ->
                "Scooter hat die Verbindung beendet"
            raw.contains("CONN_TERMINATE", true) || raw.contains("status 22") ->
                "Verbindung unterbrochen"
            raw.contains("CONN_LMP_TIMEOUT", true) || raw.contains("status 34") ->
                "Signal zu schwach – näher an den Scooter"
            raw.contains("CONN_FAIL_ESTABLISH", true) || raw.contains("status 62") ->
                "Verbindung konnte nicht aufgebaut werden"
            raw.contains("ConnectionRequest", true) && raw.contains("cancelled", true) ->
                "Verbindungsversuch abgebrochen"
            raw.contains("DeviceDisconnectedException", true) ->
                "Scooter hat die Verbindung beendet"
            raw.contains("RequestFailedException", true) -> {
                val status = Regex("status: (-?\\d+)").find(raw)?.groupValues?.get(1)
                if (status != null) "Verbindung fehlgeschlagen (Code $status)"
                else "Verbindung fehlgeschlagen"
            }
            raw.contains("timeout", true) -> "Zeitüberschreitung"
            raw.isBlank() -> "Unbekannter Verbindungsfehler"
            else -> raw
        }
    }

    fun authenticate(pin: String) = manager.authenticate(pin)
    fun changePin(newPin: String) = manager.changePin(newPin)
    fun sendControl(state: ControlState) = manager.sendControl(state)

    fun disconnect() {
        userDisconnected = true
        hadAuthSession = false
        resumeOnForeground = false
        _hint.value = null
        cancelEpisode()
        stopHeartbeat()
        manager.requestDisconnect()
        // Disconnected + keine Episode → der ForegroundController stoppt den FGS,
        // das BLE-Radio wird freigegeben, und der Roller advertised wieder für
        // andere Apps.
        _connection.value = ConnectionState.Disconnected
    }

    /**
     * Vom [eu.dns3d.epfcockpit.service.ForegroundController] aufgerufen, wenn die
     * App in den Vorder-/Hintergrund wechselt (ProcessLifecycle).
     *
     *  - Hintergrund: eine laufende Episode bewertet sofort neu (WAIT bei
     *    gesperrtem Handy, PAUSE bei entsperrt + andere App, sonst DEFER) und
     *    bricht einen laufenden Versuch ab, wenn er nicht mehr erlaubt ist.
     *    Eine stehende Verbindung bleibt unangetastet.
     *  - Vordergrund: eine laufende Episode sucht sofort aktiv (frische Runde),
     *    eine im Hintergrund beendete/zurückgestellte startet neu.
     */
    fun onAppForegroundChanged(foreground: Boolean) {
        if (appInForeground == foreground) return
        appInForeground = foreground
        if (foreground) {
            _hint.value = null
            bgRestoreStreak = 0
        }
        if (requestEpisodeReevaluation(force = false, restartIfEnding = foreground)) return
        if (!foreground) return
        val c = _connection.value
        if (resumeOnForeground && !userDisconnected &&
            c !is ConnectionState.Connected && c !is ConnectionState.Connecting
        ) {
            Log.i(TAG, "App im Vordergrund — zurückgestellten Reconnect aufnehmen")
            startEpisode(force = false, bgEligible = hadAuthSession)
        }
    }

    /**
     * Vom Service bei einer EXPLIZITEN User-Aktion (Widget / Quick-Tile /
     * Notification) aufgerufen. Erzwingt eine aktive Runde auch wenn die App im
     * Hintergrund ist, autoConnect aus ist oder der User vorher manuell getrennt
     * hat — eine bewusste Aktion darf den Roller greifen, anders als der passive
     * Auto-Reconnect. Sie quittiert auch eine Flatter-Sperre.
     */
    fun kickReconnectForAction() {
        userDisconnected = false
        resumeOnForeground = false
        bgRestoreStreak = 0
        if (_connection.value !is ConnectionState.Connected) {
            startEpisode(force = true, bgEligible = true)
        }
    }

    /**
     * Startet eine Reconnect-Episode (oder weckt/erzwingt eine laufende).
     * Setzt [reconnectStatus] SYNCHRON auf != Idle, damit [keepServiceAlive]
     * über den Abriss hinweg true bleibt.
     */
    private fun startEpisode(force: Boolean, bgEligible: Boolean) {
        synchronized(episodeLock) {
            if (!force && userDisconnected) return
            if (requestEpisodeReevaluation(force, restartIfEnding = true)) return
            resumeOnForeground = false
            _hint.value = null
            episodeForce = force
            pendingRestart = false
            pendingRestartForce = false
            sentinelDiscards = 0
            _reconnect.value = ReconnectStatus.Searching(0, MAX_RECONNECT_ATTEMPTS, null)
            // LAZY: episodeJob ist garantiert zugewiesen, bevor der Job läuft →
            // der Identitätscheck im finally von runEpisode ist sicher.
            val job = scope.launch(start = CoroutineStart.LAZY) { runEpisode(bgEligible) }
            episodeJob = job
            job.start()
        }
    }

    /**
     * Läuft eine Episode, wird sie zur Neubewertung geweckt (ggf. auf "force"
     * aufgewertet). [restartIfEnding]: endet sie gerade, startet ihr finally
     * sofort eine neue. Liefert false, wenn keine Episode läuft.
     */
    private fun requestEpisodeReevaluation(force: Boolean, restartIfEnding: Boolean): Boolean {
        synchronized(episodeLock) {
            if (episodeJob?.isActive != true) return false
            if (force) {
                episodeForce = true
                pendingRestartForce = true
            }
            if (restartIfEnding || force) pendingRestart = true
            policyTick.trySend(Unit)
            return true
        }
    }

    private fun cancelEpisode() {
        synchronized(episodeLock) {
            episodeJob?.cancel()
            episodeJob = null
            episodeForce = false
            pendingRestart = false
            pendingRestartForce = false
            _reconnect.value = ReconnectStatus.Idle
        }
        presence.disarm()
        reconnectWake?.release()
    }

    private data class AttemptResult(val connected: Boolean, val address: String)

    /**
     * Eine Episode = eine Coroutine, die bei jedem Durchlauf per
     * [ReconnectPolicy] entscheidet, wie sie weitermacht:
     *
     *  - ACTIVE (App vorne oder bewusste Aktion): der bekannte 2-Phasen-Loop —
     *    Phase 1 direkter Connect auf die gespeicherte MAC (≤6 s), Phase 2
     *    Scan-by-Name (nur bei Display an; ungefilterte Scans setzt Android bei
     *    Display aus aus), Backoff 10/20/30/60 s, Cap [MAX_RECONNECT_ATTEMPTS].
     *    Der Cap ist wichtig: ein ewig laufender aktiver Reconnect greift sich
     *    den Single-Link-Roller immer wieder weg. Nach dem Cap wartet die
     *    Episode funkstill (Exhausted), bis gesperrt / App neu geöffnet wird.
     *  - WAIT (Handy gesperrt / Display aus, oder gerade gefahren): der
     *    [ScooterPresenceWatcher] wartet im BT-Controller auf den Roller, ohne
     *    App-CPU; dazu best-effort Probes. Niemand kann den Roller in dieser
     *    Situation anderweitig wollen — ein fremder Link wird trotzdem nie übernommen.
     *  - PAUSE (entsperrt + andere App vorne): Funk frei lassen (Handover an
     *    z.B. die Hersteller-App, Commit 360c8d4), bis das Handy wieder gesperrt
     *    oder die App geöffnet wird.
     *  - DEFER: Episode beenden und beim nächsten App-Start fortsetzen — exakt
     *    das Verhalten vor diesem Umbau (Schalter aus, nie authentifiziert,
     *    Flattern, Bluetooth aus).
     *
     * Laufende Versuche werden abgebrochen, sobald die Policy sie nicht mehr
     * erlaubt (z.B. Wechsel in die Hersteller-App mitten im Connect). Die ganze
     * Episode ist auf [EPISODE_MAX_MS] begrenzt. Endet sie im Hintergrund
     * erfolglos, erscheint ein Hinweis mit „Verbinden“-Action.
     */
    private suspend fun runEpisode(initialAuth: Boolean) {
        val self = currentCoroutineContext()[Job]
        try {
            val snap = settings.flow.first()
            var addr = snap.lastScooterAddress ?: run {
                Log.i(TAG, "reconnect episode skipped (no remembered address)")
                return
            }
            if (!episodeForce && !snap.autoConnect) {
                Log.i(TAG, "auto-reconnect skipped (autoConnect=off)")
                return
            }
            val pin = snap.savedPin
            val startedAt = SystemClock.elapsedRealtime()
            var deadline = startedAt + EPISODE_MAX_MS
            var attempts = 0
            var nextAttemptAt = 0L
            var capReported = false
            var nextProbeAt = 0L
            var presenceFails = 0
            var lastPresenceFailAt = 0L
            var bluetoothOffSince = 0L
            var presenceRetryAt = 0L
            var lastMode: ReconnectPolicy.Mode? = null
            var lastForce = episodeForce
            val allowed = { evaluateMode(snap, pin, initialAuth).allowsConnecting() }

            while (currentCoroutineContext().isActive) {
                policyTick.tryReceive()
                pendingRestart = false
                pendingRestartForce = false
                val force = episodeForce
                if (!force && userDisconnected) return
                if (!force && !(cachedSnap ?: snap).autoConnect) {
                    Log.i(TAG, "auto-reconnect beendet (autoConnect=off)")
                    return
                }
                if (_connection.value is ConnectionState.Connected && manager.isConnected) {
                    onReconnected(pin)
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (force && !lastForce) {
                    attempts = 0; nextAttemptAt = 0L; capReported = false; deadline = now + EPISODE_MAX_MS
                    if (bluetoothOffSince != 0L) bluetoothOffSince = now
                }
                lastForce = force
                // Eine bewusste Aktion bei ausgeschaltetem Bluetooth verfällt nach kurzer Zeit —
                // sonst würde sie Minuten später beim Einschalten den Handover (PAUSE) aushebeln.
                // Vor evaluateMode prüfen: beim STATE_ON-Tick ist bluetoothOffSince noch gesetzt.
                if (force && bluetoothOffSince != 0L && now - bluetoothOffSince > FORCE_BLUETOOTH_GRACE_MS) {
                    Log.i(TAG, "Aktion verfallen (Bluetooth blieb aus) — passive Policy")
                    episodeForce = false
                    lastForce = false
                    continue
                }
                if (now >= deadline) {
                    Log.i(TAG, "reconnect episode: Deadline erreicht — beendet")
                    if (!isBluetoothEnabled()) {
                        resumeOnForeground = true // BT aus ist kein "Roller nicht erreichbar"
                        return
                    }
                    endEpisodeUnsuccessful(ReconnectHint.NOT_FOUND)
                    return
                }
                val mode = evaluateMode(snap, pin, initialAuth)
                if (mode != lastMode) {
                    Log.i(
                        TAG,
                        "reconnect mode → $mode (fg=$appInForeground force=$force handsOn=${interactivity.handsOn()} " +
                            "whileLocked=${(cachedSnap ?: snap).reconnectWhileLocked} auth=${initialAuth || hadAuthSession} " +
                            "pin=${!pin.isNullOrBlank()} streak=$bgRestoreStreak bt=${isBluetoothEnabled()})",
                    )
                    if (mode == ReconnectPolicy.Mode.ACTIVE && lastMode != null) {
                        // App geöffnet = frische Versuchsrunde ohne Backoff.
                        attempts = 0; nextAttemptAt = 0L; capReported = false; deadline = now + EPISODE_MAX_MS
                    }
                    if (mode != ReconnectPolicy.Mode.WAIT) {
                        presence.disarm()
                        nextProbeAt = 0L
                    }
                    if (mode != ReconnectPolicy.Mode.ACTIVE) reconnectWake?.release()
                    if (mode != ReconnectPolicy.Mode.BLUETOOTH_OFF) bluetoothOffSince = 0L
                    lastMode = mode
                }

                when (mode) {
                    ReconnectPolicy.Mode.DEFER -> {
                        Log.i(TAG, "auto-reconnect zurückgestellt bis App-Start")
                        resumeOnForeground = true
                        if (isFlapping(now)) _hint.value = ReconnectHint.FLAPPING
                        return
                    }

                    ReconnectPolicy.Mode.BLUETOOTH_OFF -> {
                        if (bluetoothOffSince == 0L) bluetoothOffSince = now
                        val until = minOf(bluetoothOffSince + BLUETOOTH_OFF_MAX_MS, deadline)
                        if (now >= until) {
                            Log.i(TAG, "Bluetooth bleibt aus — Episode beendet, Reconnect beim App-Start")
                            resumeOnForeground = true
                            return
                        }
                        _reconnect.value = ReconnectStatus.BluetoothOff(toWallClock(until))
                        // Der STATE_ON-Broadcast weckt per Tick.
                        withTimeoutOrNull(until - now) { policyTick.receive() }
                    }

                    ReconnectPolicy.Mode.ACTIVE -> {
                        if (_connection.value is ConnectionState.Connecting) {
                            // Ein anderer Versuch (z.B. ScanViewModel) läuft — nicht parallel connecten.
                            withTimeoutOrNull(CONNECTING_WAIT_MS) {
                                _connection.first { it !is ConnectionState.Connecting }
                            }
                            continue
                        }
                        if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                            if (force) {
                                // Die bewusste Aktion ist abgearbeitet — ab jetzt gilt die
                                // normale Policy (z.B. WAIT, wenn das Handy gesperrt ist).
                                episodeForce = false
                                lastForce = false
                                if (!appInForeground) {
                                    val passive = evaluateMode(snap, pin, initialAuth)
                                    if (passive == ReconnectPolicy.Mode.DEFER || !(cachedSnap ?: snap).autoConnect) {
                                        // Die passive Policy würde nicht weitersuchen → Rückmeldung geben.
                                        Log.i(TAG, "Aktion erfolglos (Cap) — Episode beendet")
                                        endEpisodeUnsuccessful(ReconnectHint.NOT_FOUND)
                                        return
                                    }
                                    Log.i(TAG, "Aktion abgearbeitet (Cap) — zurück zur passiven Policy ($passive)")
                                    attempts = 0; nextAttemptAt = 0L; capReported = false
                                    continue
                                }
                            }
                            if (!capReported) {
                                capReported = true
                                Log.i(TAG, "auto-reconnect: Cap ($MAX_RECONNECT_ATTEMPTS Versuche) erreicht — pausiert, Funk freigegeben")
                                if (_connection.value !is ConnectionState.Connected && !userDisconnected) {
                                    _connection.value = ConnectionState.Failed(
                                        "Roller nicht erreichbar – Auto-Suche pausiert, bitte erneut verbinden",
                                    )
                                }
                                reconnectWake?.release()
                            }
                            _reconnect.value = ReconnectStatus.Exhausted(toWallClock(deadline))
                            withTimeoutOrNull(deadline - now) { policyTick.receive() }
                            continue
                        }
                        if (now < nextAttemptAt) {
                            withTimeoutOrNull(nextAttemptAt - now) { policyTick.receive() }
                            continue
                        }
                        attempts++
                        _reconnect.value = ReconnectStatus.Searching(attempts, MAX_RECONNECT_ATTEMPTS, null)
                        if (!appInForeground) reconnectWake?.acquire(ACTIVE_BG_WAKE_MS)

                        val attemptNo = attempts
                        val result = runAbortable(allowed) {
                            activeAttempt(addr, snap.lastScooterName, attemptNo, allowed)
                        }
                        if (result == null) {
                            Log.i(TAG, "auto-reconnect attempt #$attemptNo abgebrochen (Policy geändert)")
                            failIfDisconnected()
                            continue
                        }
                        addr = result.address
                        if (result.connected) {
                            onReconnected(pin)
                            return
                        }
                        failIfDisconnected()
                        // Nach dem letzten Versuch nicht noch 60 s warten — der
                        // nächste Durchlauf meldet sofort den Cap.
                        if (attempts < MAX_RECONNECT_ATTEMPTS) {
                            val d = ReconnectPolicy.backoffMs(attempts)
                            nextAttemptAt = SystemClock.elapsedRealtime() + d
                            _reconnect.value = ReconnectStatus.Searching(
                                attempts, MAX_RECONNECT_ATTEMPTS, System.currentTimeMillis() + d,
                            )
                            Log.i(TAG, "auto-reconnect attempt #$attempts failed, waiting ${d}ms")
                        }
                    }

                    ReconnectPolicy.Mode.WAIT -> {
                        // Absoluter Retry-Abstand nach einem gescheiterten Presence-Connect —
                        // Ticks (z.B. das eigene ACL_DISCONNECTED) verkürzen ihn nicht.
                        if (now < presenceRetryAt) {
                            withTimeoutOrNull(presenceRetryAt - now) { policyTick.receive() }
                            continue
                        }
                        // Den Guard nur vor dem Armieren prüfen — danach zählt der
                        // eigene Sentinel-Link mit.
                        if (!presence.isArmed && foreignGattLink(addr)) {
                            Log.i(TAG, "Roller hat bereits einen GATT-Link (andere App?) — Sentinel nicht armiert")
                            _reconnect.value = ReconnectStatus.ForeignLink(toWallClock(deadline))
                            withTimeoutOrNull(FOREIGN_RECHECK_MS) { policyTick.receive() }
                            continue
                        }
                        _reconnect.value = ReconnectStatus.Waiting(toWallClock(deadline))
                        if (!presence.isArmed) {
                            presenceEvents.tryReceive()
                            presence.arm(addr)
                        }
                        // Absoluter Probe-Termin: Display-Ticks dürfen ihn nicht verschieben.
                        if (nextProbeAt == 0L) {
                            nextProbeAt = now + if (now - startedAt < PROBE_FAST_PHASE_MS) PROBE_INTERVAL_FAST_MS
                                else PROBE_INTERVAL_SLOW_MS
                        }
                        val present = withTimeoutOrNull((nextProbeAt - now).coerceAtLeast(1L)) {
                            select<Boolean> {
                                presenceEvents.onReceive { true }
                                policyTick.onReceive { false }
                            }
                        }
                        when (present) {
                            true -> {
                                postConnectWake?.acquire(POST_CONNECT_WAKE_MS)
                                if (!allowed()) {
                                    postConnectWake?.release()
                                    continue // z.B. gerade entsperrt → PAUSE
                                }
                                // Der Sentinel meldet JEDEN Link zum Roller — auch den der
                                // Hersteller-App. Sentinel lösen, kurz warten: bleibt der Link
                                // bestehen, gehört er einer anderen App → nicht übernehmen.
                                presence.disarm()
                                delay(FOREIGN_SETTLE_MS)
                                if (foreignGattLink(addr)) {
                                    Log.i(TAG, "Roller-Link gehört einer anderen App — nicht übernehmen")
                                    postConnectWake?.release()
                                    _reconnect.value = ReconnectStatus.ForeignLink(toWallClock(deadline))
                                    withTimeoutOrNull(FOREIGN_RECHECK_MS) { policyTick.receive() }
                                    continue
                                }
                                Log.i(TAG, "Roller wieder erreichbar — verbinde")
                                val ok = runAbortable(allowed) { tryDirectConnect(addr, PRESENCE_CONNECT_TIMEOUT_MS) }
                                if (ok == true) {
                                    onReconnected(pin)
                                    return
                                }
                                failIfDisconnected()
                                if (ok == null) continue
                                val t = SystemClock.elapsedRealtime()
                                if (t - lastPresenceFailAt > PRESENCE_FAIL_DECAY_MS) presenceFails = 0
                                lastPresenceFailAt = t
                                if (++presenceFails >= PRESENCE_FAIL_LIMIT) {
                                    if (episodeForce || appInForeground) {
                                        presenceFails = 0
                                        continue
                                    }
                                    Log.i(TAG, "Sentinel meldet Roller, Connect scheitert wiederholt — Episode beendet")
                                    endEpisodeUnsuccessful(ReconnectHint.NOT_FOUND)
                                    return
                                }
                                presenceRetryAt = SystemClock.elapsedRealtime() + PRESENCE_RETRY_GAP_MS
                            }
                            // Best-effort-Probe: der Timer steht im CPU-Suspend, der Sentinel nicht.
                            null -> {
                                nextProbeAt = 0L
                                // Verbundenes Gerät hier = eigener Sentinel (Event folgt) oder fremde App.
                                if (!allowed() || foreignGattLink(addr)) continue
                                val ok = runAbortable(allowed) { tryDirectConnect(addr, DIRECT_CONNECT_TIMEOUT_MS) }
                                if (ok == true) {
                                    onReconnected(pin)
                                    return
                                }
                                failIfDisconnected()
                            }
                            false -> Unit // Neubewertung (Display, Vordergrund, Settings, Aktion)
                        }
                    }

                    ReconnectPolicy.Mode.PAUSE -> {
                        _reconnect.value = ReconnectStatus.Paused(toWallClock(deadline))
                        withTimeoutOrNull(deadline - now) { policyTick.receive() }
                    }
                }
            }
        } finally {
            presence.disarm()
            reconnectWake?.release()
            synchronized(episodeLock) {
                if (episodeJob === self) {
                    val restart = pendingRestart && !userDisconnected && _connection.value !is ConnectionState.Connected
                    val restartForce = pendingRestartForce
                    episodeJob = null
                    episodeForce = false
                    pendingRestart = false
                    pendingRestartForce = false
                    _reconnect.value = ReconnectStatus.Idle
                    if (restart) {
                        Log.i(TAG, "reconnect episode: Anfrage während des Endes — starte neue Episode (force=$restartForce)")
                        startEpisode(force = restartForce, bgEligible = hadAuthSession || restartForce)
                    }
                }
            }
        }
    }

    private fun evaluateMode(snap: SettingsStore.Snapshot, pin: String?, initialAuth: Boolean): ReconnectPolicy.Mode {
        val now = SystemClock.elapsedRealtime()
        return ReconnectPolicy.decide(
            bluetoothOn = isBluetoothEnabled(),
            force = episodeForce,
            appForeground = appInForeground,
            reconnectWhileLocked = (cachedSnap ?: snap).reconnectWhileLocked,
            backgroundEligible = (initialAuth || hadAuthSession) && !pin.isNullOrBlank(),
            flapping = isFlapping(now),
            deviceLocked = !interactivity.handsOn(),
            ridingRecently = lastMovingAtElapsed != 0L && now - lastMovingAtElapsed < RIDING_WINDOW_MS,
        )
    }

    private fun ReconnectPolicy.Mode.allowsConnecting(): Boolean =
        this == ReconnectPolicy.Mode.ACTIVE || this == ReconnectPolicy.Mode.WAIT

    /** Flattern zählt nur, solange die letzte Hintergrund-Wiederherstellung frisch ist. */
    private fun isFlapping(now: Long): Boolean =
        bgRestoreStreak >= FLAP_LIMIT && now - lastBgRestoreAtElapsed < FLAP_WINDOW_MS

    /**
     * Führt [block] aus, bricht ihn aber ab (→ null), sobald ein policyTick zeigt,
     * dass [stillAllowed] nicht mehr gilt. So endet z.B. ein laufender Connect
     * sofort, wenn der User in die Hersteller-App wechselt. Der Tick wird für
     * die Neubewertung am Schleifenanfang erneut gesendet.
     */
    private suspend fun <T> runAbortable(stillAllowed: () -> Boolean, block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        var result: T? = null
        var finished = false
        var sawTick = false
        while (!finished) {
            val done = select<Boolean> {
                work.onAwait { true }
                policyTick.onReceive { false }
            }
            if (done) {
                result = work.await()
                finished = true
            } else {
                sawTick = true
                if (!stillAllowed()) {
                    work.cancelAndJoin()
                    finished = true
                }
            }
        }
        // Verbrauchte Ticks weiterreichen, damit nachfolgende Wartezeiten sofort neu bewerten.
        if (sawTick) policyTick.trySend(Unit)
        result
    }

    /** Ein aktiver Versuch: Phase 1 Direct-Connect, Phase 2 Scan-by-Name (nur bei Display an). */
    private suspend fun activeAttempt(
        address: String,
        savedName: String?,
        attempt: Int,
        allowed: () -> Boolean,
    ): AttemptResult {
        Log.i(TAG, "auto-reconnect attempt #$attempt — phase 1 (direct to $address)")
        if (tryDirectConnect(address, DIRECT_CONNECT_TIMEOUT_MS)) return AttemptResult(true, address)
        if (!allowed()) return AttemptResult(false, address)
        if (!interactivity.isInteractive()) {
            Log.i(TAG, "phase 2 übersprungen (Display aus — ungefilterte Scans sind dann gesperrt)")
            return AttemptResult(false, address)
        }
        Log.i(TAG, "auto-reconnect attempt #$attempt — phase 2 (scan by name '$savedName')")
        val found = scanForScooter(address, savedName) ?: return AttemptResult(false, address)
        if (!allowed()) return AttemptResult(false, found)
        return AttemptResult(tryDirectConnect(found, SCAN_CONNECT_TIMEOUT_MS), found)
    }

    /** Direkter Connect mit Timeout; true wenn danach verbunden. Episode-Cancel wird durchgereicht. */
    private suspend fun tryDirectConnect(address: String, timeoutMs: Long): Boolean {
        try {
            withTimeoutOrNull(timeoutMs) { connect(address) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "direct connect failed: ${t.message}")
        }
        return _connection.value is ConnectionState.Connected
    }

    /** Phase 2: Scan-by-Name (Privacy-MAC kann rotiert sein). Liefert die frische Adresse. */
    private suspend fun scanForScooter(savedAddr: String, savedName: String?): String? {
        val match = try {
            withTimeoutOrNull(10_000L) {
                scan(scootersOnly = true).firstOrNull { found ->
                    (!savedName.isNullOrBlank() &&
                        found.name?.equals(savedName, ignoreCase = true) == true) ||
                        found.address.equals(savedAddr, ignoreCase = true)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "phase 2 scan failed: ${t.message}")
            null
        } ?: return null
        settings.rememberScooter(match.address, match.name)
        return match.address
    }

    private fun onReconnected(savedPin: String?) {
        presence.disarm()
        // Re-Auth (500 ms delay), Init-Burst und Heartbeat-Start gegen CPU-Suspend absichern.
        postConnectWake?.acquire(POST_CONNECT_WAKE_MS)
        if (!appInForeground) {
            val now = SystemClock.elapsedRealtime()
            bgRestoreStreak = if (now - lastBgRestoreAtElapsed < FLAP_WINDOW_MS) bgRestoreStreak + 1 else 1
            lastBgRestoreAtElapsed = now
            Log.i(TAG, "reconnected im Hintergrund (streak=$bgRestoreStreak)")
        }
        reauthenticateIfPossible(savedPin)
    }

    /** connect() setzt bei Timeout Disconnected — während einer Episode ist das ein Fehlschlag, kein Trennen. */
    private fun failIfDisconnected() {
        if (_connection.value is ConnectionState.Disconnected && !userDisconnected) {
            _connection.value = ConnectionState.Failed("Roller nicht erreichbar")
        }
    }

    private fun endEpisodeUnsuccessful(hint: ReconnectHint) {
        if (_connection.value !is ConnectionState.Connected && !userDisconnected) {
            _connection.value = ConnectionState.Failed("Roller nicht erreichbar – Auto-Suche beendet")
        }
        // Auch im Vordergrund: der nächste App-Start (ON_START) nimmt wieder auf.
        resumeOnForeground = true
        if (!appInForeground) _hint.value = hint
    }

    /**
     * Hält schon ein anderer GATT-Client (z.B. die Hersteller-App) einen Link zum
     * Roller? getConnectedDevices(GATT) liefert die Links aller Apps.
     */
    @SuppressLint("MissingPermission")
    private fun foreignGattLink(address: String): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)
            ?.getConnectedDevices(BluetoothProfile.GATT)
            ?.any { it.address.equals(address, ignoreCase = true) } == true
    }.getOrDefault(false)

    private fun toWallClock(elapsedRealtime: Long): Long =
        System.currentTimeMillis() + (elapsedRealtime - SystemClock.elapsedRealtime())

    /**
     * Nach Auto-Reconnect-Connect die gespeicherte PIN erneut senden — sonst
     * bleibt der Status auf "Connected (authenticated=false)" und die App
     * sieht zwar das BLE-Link, aber der Scooter beantwortet keine
     * Telemetrie-Notifications. Wenn keine PIN gespeichert ist (User hat
     * den "PIN nicht merken"-Pfad gewählt), bleibt das manueller Pfad.
     */
    private fun reauthenticateIfPossible(savedPin: String?) {
        if (savedPin.isNullOrBlank()) {
            Log.i(TAG, "post-reconnect: no saved PIN, skipping re-auth")
            return
        }
        Log.i(TAG, "post-reconnect: re-auth with saved PIN")
        // Kurze Pause, damit der Manager die Init-Phase fertig macht
        // (initialize() = MTU + Notification-Subscriptions). Vorher wird
        // sendAt() einen null highTx haben.
        scope.launch {
            kotlinx.coroutines.delay(500)
            authenticate(savedPin)
        }
    }

    private fun friendlyDisconnectReason(reason: Int): String = when (reason) {
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_TERMINATE_PEER_USER ->
            "Scooter hat die Verbindung beendet"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_TERMINATE_LOCAL_HOST ->
            "Verbindung vom Handy beendet"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_LINK_LOSS ->
            "Verbindung verloren – Signal weg"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_TIMEOUT ->
            "Verbindung verloren – Signal weg"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_NOT_SUPPORTED ->
            "Gerät nicht unterstützt"
        no.nordicsemi.android.ble.observer.ConnectionObserver.REASON_CANCELLED ->
            "Verbindungsversuch abgebrochen"
        else -> "Verbindung unterbrochen (reason=$reason)"
    }

    companion object {
        private const val TAG = "BleRepository"
        /** Pause zwischen Trip-Reset-Frame und dem Frame, das das Reset-Bit wieder löscht. */
        private const val TRIP_RESET_RELEASE_MS = 600L
        /** So lange gilt ein kommandierter Licht-Zustand, bevor die Telemetrie gewinnt. */
        private const val LIGHT_CONFIRM_MS = 2_000L

        /**
         * Maximale AKTIVE Reconnect-Versuche pro Runde, bevor wir aufgeben und
         * den Funk freigeben. Mit dem Backoff (10/20/30/60 s) sind das ~2-3 min —
         * genug für "Roller war kurz aus der Reichweite", aber nicht ewig, damit
         * andere Apps den Single-Link-Roller wieder bekommen können. Das passive
         * Sentinel-Warten bei gesperrtem Handy zählt nicht dazu.
         */
        private const val MAX_RECONNECT_ATTEMPTS = 5

        /** Obergrenze einer Episode (FGS + Notification bleiben so lange sichtbar). */
        private const val EPISODE_MAX_MS = 120 * 60_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 6_000L
        private const val SCAN_CONNECT_TIMEOUT_MS = 15_000L
        private const val PRESENCE_CONNECT_TIMEOUT_MS = 10_000L
        private const val PRESENCE_FAIL_LIMIT = 3
        private const val PRESENCE_RETRY_GAP_MS = 10_000L
        private const val PROBE_FAST_PHASE_MS = 10 * 60_000L
        private const val PROBE_INTERVAL_FAST_MS = 60_000L
        private const val PROBE_INTERVAL_SLOW_MS = 5 * 60_000L
        private const val FOREIGN_RECHECK_MS = 30_000L
        /** So lange wartet eine Episode bei ausgeschaltetem Bluetooth, bevor sie aufgibt. */
        private const val BLUETOOTH_OFF_MAX_MS = 10 * 60_000L
        private const val SENTINEL_REARM_LIMIT = 3
        /** Wie lange eine bewusste Aktion bei ausgeschaltetem Bluetooth "scharf" bleibt. */
        private const val FORCE_BLUETOOTH_GRACE_MS = 60_000L
        /** Nach dem Lösen des Sentinels: so lange warten, bis ein nur von ihm gehaltener Link weg ist. */
        private const val FOREIGN_SETTLE_MS = 3_000L
        /** Presence-Fehlschläge, die länger auseinanderliegen, zählen nicht zusammen. */
        private const val PRESENCE_FAIL_DECAY_MS = 5 * 60_000L
        private const val CONNECTING_WAIT_MS = 20_000L

        /** "Gerade gefahren": Abriss mit Navi vorne ist kein Handover-Fall. */
        private const val RIDING_SPEED_KMH = 3f
        private const val RIDING_WINDOW_MS = 120_000L

        /** Flatter-Schutz: so viele Hintergrund-Wiederherstellungen, je < FLAP_WINDOW_MS auseinander → DEFER. */
        private const val FLAP_LIMIT = 6
        private const val FLAP_WINDOW_MS = 10 * 60_000L

        private const val ACTIVE_BG_WAKE_MS = 8 * 60_000L
        private const val POST_CONNECT_WAKE_MS = 20_000L
        private const val HB_STARVATION_MS = 2_000L
        private const val HB_WAKE_TIMEOUT_MS = 60 * 60_000L
        private const val HB_WAKE_RENEW_MARGIN_MS = 5 * 60_000L

        /** Soll der Foreground-Service laufen? (Single Source of Truth für Controller + Service.) */
        fun shouldKeepServiceAlive(c: ConnectionState, r: ReconnectStatus): Boolean =
            c is ConnectionState.Connected || c is ConnectionState.Connecting || r !is ReconnectStatus.Idle
    }

    /**
     * Nach PinOk: Init-Burst (5× PULSE_INIT) und danach der Heartbeat — exakt
     * wie die offizielle App. Der Burst-Job wird gehalten, damit ein Abriss
     * währenddessen keinen verwaisten Heartbeat auf dem toten Link startet.
     */
    private fun startInitBurstThenHeartbeat() {
        synchronized(heartbeatLock) {
            initBurstJob?.cancel()
            initBurstJob = scope.launch {
                repeat(5) {
                    manager.writeLowFrame(ControlFrames.PULSE_INIT, label = "init")
                    delay(50)
                }
                startHeartbeat()
            }
        }
    }

    /**
     * 600-ms-Heartbeat. Ohne ihn streamt der Roller keine Telemetrie und kappt
     * nach ~30 s. delay() läuft auf der Uptime-Uhr, die im CPU-Suspend steht —
     * wurde einmal eine Lücke > [HB_STARVATION_MS] gemessen (im Loop oder beim
     * Abriss), hält der Loop ab dann bei Display aus eine Partial-WakeLock
     * (adaptiv: nur Geräte, die es brauchen).
     */
    private fun startHeartbeat() {
        synchronized(heartbeatLock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            if (!manager.isConnected || _connection.value !is ConnectionState.Connected) {
                Log.i(TAG, "heartbeat nicht gestartet — Link bereits weg")
                return
            }
            lastHeartbeatTickElapsed = SystemClock.elapsedRealtime()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext()[Job]
                var acquiredAt = 0L
                try {
                    var last = SystemClock.elapsedRealtime()
                    while (isActive) {
                        if (_connection.value !is ConnectionState.Connected) break
                        manager.writeLowFrame(ControlFrames.PULSE_HEARTBEAT, label = "hb")
                        val now = SystemClock.elapsedRealtime()
                        lastHeartbeatTickElapsed = now
                        val wl = heartbeatWake
                        if (wl != null) {
                            val needLock = heartbeatStarved && !interactivity.isInteractive()
                            // Vor Ablauf erneuern (non-refcounted acquire setzt nur den Timeout
                            // zurück) — sonst fiele der Schutz jede Stunde kurz weg.
                            if (needLock && (!wl.isHeld || now - acquiredAt > HB_WAKE_TIMEOUT_MS - HB_WAKE_RENEW_MARGIN_MS)) {
                                wl.acquire(HB_WAKE_TIMEOUT_MS)
                                acquiredAt = now
                            } else if (!needLock && wl.isHeld) {
                                wl.release()
                            }
                        }
                        delay(600)
                        val after = SystemClock.elapsedRealtime()
                        if (!heartbeatStarved && after - last > HB_STARVATION_MS) {
                            Log.w(TAG, "heartbeat starvation: ${after - last} ms (CPU-Suspend) → WakeLock ab jetzt bei Display aus")
                            heartbeatStarved = true
                        }
                        last = after
                    }
                } finally {
                    // Release NACH dem letzten acquire dieses Loops — ein release aus
                    // stopHeartbeat() könnte sonst von einem späten acquire überholt werden.
                    synchronized(heartbeatLock) {
                        if (heartbeatJob === self || heartbeatJob == null) heartbeatWake?.release()
                        if (heartbeatJob === self) heartbeatJob = null
                    }
                }
            }
            heartbeatJob = job
            job.start()
        }
    }

    private fun stopHeartbeat() {
        synchronized(heartbeatLock) {
            initBurstJob?.cancel()
            initBurstJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            // Die WakeLock gibt das finally des Loops frei. Lief gar kein Loop,
            // ist sie ohnehin nicht gehalten.
        }
    }

    /**
     * Beim Abriss: lag der letzte Heartbeat länger als [HB_STARVATION_MS] zurück,
     * hat der CPU-Suspend den Heartbeat ausgehungert — genau der Fall, den der
     * Loop selbst nicht mehr messen kann, weil er vor dem Ende seines delay()
     * gecancelt wird.
     */
    private fun noteHeartbeatStarvationAtDisconnect() {
        val running = synchronized(heartbeatLock) { heartbeatJob?.isActive == true }
        if (!running || heartbeatStarved) return
        val gap = SystemClock.elapsedRealtime() - lastHeartbeatTickElapsed
        if (gap > HB_STARVATION_MS) {
            Log.w(TAG, "heartbeat starvation beim Abriss erkannt: letzter Heartbeat vor $gap ms → WakeLock ab jetzt bei Display aus")
            heartbeatStarved = true
        }
    }

    // === High-level Pulse Plus controls (sniff-verified) ===
    // Frame g3 = cachedMaxSpeedKmh (vom Echo-Sync gepflegt) — der Controller
    // bekommt bei jedem Park/Mode-Toggle den aktuellen User-Max-Speed mit,
    // damit der nicht durch das nächste Toggle wieder auf einen alten Wert
    // resettet wird.

    /** Licht-Bit für das nächste Statusframe: aktueller Zustand. */
    private fun currentLight(): Boolean = _lightOn.value == true

    /**
     * Statusframe für den AKTUELLEN Park/Mode-Zustand (Latch + gewählter Mode).
     * Unbekannter Park-Zustand zählt als geparkt — lieber sperren als
     * ungewollt entsperren. Caller sperren die UI bei unbekanntem Zustand.
     */
    private fun currentStateFrame(light: Boolean = currentLight(), resetTrip: Boolean = false): ByteArray =
        ControlFrames.pulseState(
            parked = parkController.parkingDisplay.value != false,
            stand = cachedRideModeStand,
            light = light,
            resetTrip = resetTrip,
            g3MaxKmh = cachedMaxSpeedKmh,
        )

    /**
     * Entsperren schaltet das Licht ein, wenn der User das in den Settings
     * aktiviert hat. Liefert das Licht-Bit für das Entsperr-Frame und zieht
     * den Licht-Zustand optimistisch mit.
     */
    private fun lightForUnlock(): Boolean {
        if (cachedAutoLightOnUnlock && !currentLight()) {
            Log.i(TAG, "auto-light: Licht beim Entsperren an")
            commandLight(true)
        }
        return currentLight()
    }

    fun setParking(on: Boolean) {
        if (!on) lightForUnlock()
        // Park-OFF im aktuell gewählten Mode senden, damit ein Park-OFF nicht
        // ungewollt auf Kick wechselt: der generische parkingOff-Frame (0x82)
        // IST der Kick-Frame. Stand → 0xA2 (entparkt + Stand), Kick → 0x82.
        // Frame pro Resend frisch bauen (Licht kann sich dazwischen ändern).
        parkController.setParking(target = on, label = if (on) "park-on" else "park-off") {
            ControlFrames.pulseState(
                parked = on,
                stand = cachedRideModeStand,
                light = currentLight(),
                g3MaxKmh = cachedMaxSpeedKmh,
            )
        }
    }

    /**
     * Ride mode toggle. `stand=true` → Stand-Stop (must be pushed before
     * motor engages). `stand=false` → Kick-Start (goes on throttle alone).
     * NB: both frames also unpark the scooter — the official app uses the
     * same frames for "Parking OFF" and the mode buttons.
     */
    fun setRideMode(stand: Boolean) {
        val wasParked = parkController.parkingDisplay.value == true
        val light = if (wasParked) lightForUnlock() else currentLight()
        val frame = ControlFrames.pulseState(
            parked = false, stand = stand, light = light, g3MaxKmh = cachedMaxSpeedKmh,
        )
        // Direkter Write, NICHT durch die Verify-Logik (sonst Spinner/Fehler am
        // falschen Control). Ein Mode-Wechsel deaktiviert physisch das Parken,
        // also den Park-Latch sofort auf "entparkt" setzen, damit der Switch
        // mitzieht und der State stimmt.
        cachedRideModeStand = stand
        manager.writeLowFrame(frame, label = if (stand) "mode-stand" else "mode-kick")
        parkController.notePark(false)
    }

    /**
     * Setzt die Max-Geschwindigkeit (byte [7] = g3) durch ein Stand-Stop-Frame
     * mit dem neuen Wert — mirror dem Verhalten der offiziellen EPF-App, die
     * beim Slider-Move ebenfalls Stand sendet (Sniff #3 bestätigt). Der
     * neue Wert wird persistiert und in den Cache geschrieben, sodass das
     * nächste Park-/Mode-Frame ihn weiter trägt.
     *
     * Caller-Pflicht: nur aufrufen wenn der Roller geparkt (oder steht) ist —
     * sonst wechselt der Roller mid-ride in Stand-Stop, das ist kein guter
     * Tag. Die Settings-UI disabled den Button entsprechend.
     */
    fun setMaxSpeed(kmh: Int) {
        val clamped = kmh.coerceIn(1, 40)
        cachedMaxSpeedKmh = clamped
        val frame = ControlFrames.modeStandStop(clamped, light = currentLight())
        // Direkter Write, NICHT durch die Verify-Logik (kein Phantom-Park-Pending
        // auf Dashboard/Widget/Tile). Das Frame ist ein Stand-Mode-Frame und
        // deaktiviert physisch das Parken — Latch entsprechend auf "entparkt".
        manager.writeLowFrame(frame, label = "set-maxspeed-$clamped")
        parkController.notePark(false)
        scope.launch { settings.setGearLimits(g1 = 6, g2 = 15, g3 = clamped) }
    }

    /**
     * Frontlicht schalten: aktueller Mode-Zustand + neues Licht-Bit. Nur
     * entsperrt — geparkt ignoriert der Controller das Bit (live verifiziert).
     * Zweimal gesendet (blind retry wie beim Parken) — das Frame ist ein
     * absoluter Zustand, Doppel-Empfang ist harmlos.
     */
    fun setLight(on: Boolean) {
        if (parkController.parkingDisplay.value != false) return
        commandLight(on)
        controlJob?.cancel()
        controlJob = scope.launch {
            repeat(2) { i ->
                manager.writeLowFrame(currentStateFrame(light = on), label = if (on) "light-on" else "light-off")
                if (i == 0) delay(150)
            }
        }
    }

    /**
     * Tageskilometer (Trip) am Roller zurücksetzen. Die offizielle App setzt
     * dafür im Statusframe Bit 3 (`atmosphereLightSw`). Wir senden den
     * aktuellen Zustand einmal MIT Reset-Bit und kurz danach ohne, damit das
     * Bit nicht im Controller stehen bleibt.
     */
    fun resetTrip() {
        if (parkController.parkingDisplay.value == null) return
        controlJob?.cancel()
        controlJob = scope.launch {
            manager.writeLowFrame(currentStateFrame(resetTrip = true), label = "trip-reset")
            delay(TRIP_RESET_RELEASE_MS)
            manager.writeLowFrame(currentStateFrame(), label = "trip-reset-release")
        }
    }

    private var controlJob: Job? = null

    fun setBlinkerton(on: Boolean) = manager.sendAt(AtCommands.setVoiceOff(on))
}
