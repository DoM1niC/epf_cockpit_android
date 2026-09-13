package eu.dns3d.epfcockpit.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.AtResponse
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.data.DashboardLayout
import eu.dns3d.epfcockpit.data.SettingsStore
import eu.dns3d.epfcockpit.data.ThemeMode
import eu.dns3d.epfcockpit.streaming.ScooterNameApi
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val gear1: Int = 6,
    val gear2: Int = 15,
    val gear3: Int = 20,
    val autoConnect: Boolean = true,
    /** Nach Abbruch auch bei gesperrtem Handy selbst neu verbinden. */
    val reconnectWhileLocked: Boolean = true,
    val scooterName: String? = null,
    val scooterAddress: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dashboardLayout: DashboardLayout = DashboardLayout.STANDARD,
    val streamingUrl: String = "",
    val streamingEnabled: Boolean = false,
    val streamingToken: String = "",
    /** True wenn authentifiziert verbunden — Voraussetzung um Max-Speed zu senden. */
    val authenticated: Boolean = false,
    /** True wenn Roller parkt — Voraussetzung um Max-Speed *sicher* zu senden. */
    val parking: Boolean = false,
    /** Opt-in für GPS-Waypoints pro Trip, default OFF. */
    val trackLocation: Boolean = false,
    /** Opt-in: GPS auch im Idle/Parking an das Dashboard senden (nur live, nicht in Room). */
    val trackLocationIdle: Boolean = false,
    /** Vom User gesetzter Anzeigename des Rollers — leer = Fallback "E-Scooter". */
    val scooterFriendlyName: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ble: BleRepository,
    private val store: SettingsStore,
    private val scooterNameApi: ScooterNameApi,
    streamer: TelemetryStreamer,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        store.flow,
        ble.connection,
        ble.telemetry,
    ) { s, conn, tele ->
        SettingsUiState(
            gear1 = s.gear1MaxKmh,
            gear2 = s.gear2MaxKmh,
            gear3 = s.gear3MaxKmh,
            autoConnect = s.autoConnect,
            reconnectWhileLocked = s.reconnectWhileLocked,
            scooterName = s.lastScooterName,
            scooterAddress = s.lastScooterAddress,
            themeMode = s.themeMode,
            dashboardLayout = s.dashboardLayout,
            streamingUrl = s.streamingUrl.orEmpty(),
            streamingEnabled = s.streamingEnabled,
            streamingToken = s.streamingToken.orEmpty(),
            authenticated = (conn as? ConnectionState.Connected)?.authenticated == true,
            parking = tele?.parking == true,
            trackLocation = s.trackLocation,
            trackLocationIdle = s.trackLocationIdle,
            scooterFriendlyName = s.scooterFriendlyName.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    val streamingStatus: StateFlow<TelemetryStreamer.Status> = streamer.status

    private val _pinStatus = MutableStateFlow<String?>(null)
    val pinStatus: StateFlow<String?> = _pinStatus.asStateFlow()

    /** Statushinweis fuer den Anzeigename-Sync, z.B. "Vom Server uebernommen". */
    private val _scooterNameSyncStatus = MutableStateFlow<String?>(null)
    val scooterNameSyncStatus: StateFlow<String?> = _scooterNameSyncStatus.asStateFlow()

    // Welche MACs wir in dieser VM-Instanz schon vom Server abgefragt haben —
    // damit der Pull-Side-Effect bei jeder Settings-Aenderung nicht endlos
    // refetched. Reset on forget/disconnect-of-address waere nice, aber
    // single-user und der User dreht selten zwischen mehreren Scootern.
    private val pulledAddresses = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            ble.atReplies.collect { reply ->
                if (reply is AtResponse.PinChanged) _pinStatus.value = "PIN aktualisiert"
            }
        }
        // Server-Pull: wenn lastScooterAddress UND streamingUrl/Token verfuegbar
        // sind und Streaming enabled ist, aber lokal noch kein Name gesetzt
        // ist — hole ihn vom Server. Verhindert dass der User auf einem neuen
        // Smartphone den Namen re-tippen muss.
        viewModelScope.launch {
            store.flow
                .map { s ->
                    PullKey(
                        address = s.lastScooterAddress,
                        url = s.streamingUrl,
                        token = s.streamingToken,
                        enabled = s.streamingEnabled,
                        localName = s.scooterFriendlyName,
                    )
                }
                .distinctUntilChanged()
                .collect { key ->
                    val addr = key.address?.trim().orEmpty()
                    val url = key.url?.trim().orEmpty()
                    if (!key.enabled || addr.isEmpty() || url.isEmpty()) return@collect
                    if (!key.localName.isNullOrBlank()) return@collect
                    if (!pulledAddresses.add(addr)) return@collect
                    val remote = scooterNameApi.fetch(url, key.token, addr) ?: return@collect
                    // Race: User tippt waehrend des Fetches → Local wurde
                    // inzwischen non-empty. Dann nicht ueberschreiben.
                    val nowLocal = store.flow.first().scooterFriendlyName
                    if (!nowLocal.isNullOrBlank()) return@collect
                    store.setScooterFriendlyName(remote.name)
                    _scooterNameSyncStatus.value = "Name vom Server übernommen"
                    Log.i(TAG, "scooterName pulled from server: '${remote.name}' for $addr")
                }
        }
    }

    private data class PullKey(
        val address: String?,
        val url: String?,
        val token: String?,
        val enabled: Boolean,
        val localName: String?,
    )

    fun setGears(g1: Int, g2: Int, g3: Int) {
        viewModelScope.launch { store.setGearLimits(g1, g2, g3) }
    }

    /**
     * Schickt einen Control-Frame mit neuer g3-Max-Speed an den Roller und
     * persistiert den Wert lokal. Sollte nur aus dem UI aufgerufen werden wenn
     * der Roller geparkt + authentifiziert verbunden ist (Apply-Button-Guard).
     */
    fun setMaxSpeed(kmh: Int) {
        ble.setMaxSpeed(kmh)
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { store.setAutoConnect(enabled) }
    }

    fun setReconnectWhileLocked(enabled: Boolean) {
        viewModelScope.launch { store.setReconnectWhileLocked(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setDashboardLayout(mode: DashboardLayout) {
        viewModelScope.launch { store.setDashboardLayout(mode) }
    }

    fun setStreamingUrl(url: String) {
        viewModelScope.launch { store.setStreamingUrl(url) }
    }

    fun setStreamingEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setStreamingEnabled(enabled) }
    }

    fun setStreamingToken(token: String) {
        viewModelScope.launch { store.setStreamingToken(token) }
    }

    fun setTrackLocation(enabled: Boolean) {
        viewModelScope.launch { store.setTrackLocation(enabled) }
    }

    fun setTrackLocationIdle(enabled: Boolean) {
        viewModelScope.launch { store.setTrackLocationIdle(enabled) }
    }

    fun setScooterFriendlyName(name: String) {
        viewModelScope.launch {
            store.setScooterFriendlyName(name)
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@launch
            // Fire-and-forget Push an den Server. Wir warten NICHT auf das
            // Ergebnis — der Hello-Frame des naechsten Streaming-Reconnects
            // wuerde den Namen sowieso re-uploaden. Status-Hint sofort
            // optimistisch setzen.
            val snap = store.flow.first()
            val url = snap.streamingUrl?.trim().orEmpty()
            val addr = snap.lastScooterAddress?.trim().orEmpty()
            if (!snap.streamingEnabled || url.isEmpty() || addr.isEmpty()) return@launch
            val ok = scooterNameApi.push(url, snap.streamingToken, addr, trimmed)
            _scooterNameSyncStatus.value = if (ok) "Mit Server synchronisiert" else "Server-Sync fehlgeschlagen"
        }
    }

    fun changePin(newPin: String) {
        if (newPin.length != 6 || newPin.any { !it.isDigit() }) return
        ble.changePin(newPin)
        viewModelScope.launch { store.savePin(newPin) }
    }

    fun forgetScooter() {
        viewModelScope.launch {
            ble.disconnect()
            store.forgetScooter()
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
