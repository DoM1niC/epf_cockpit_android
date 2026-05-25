package eu.dns3d.epfcockpit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.AtResponse
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.data.SettingsStore
import eu.dns3d.epfcockpit.data.ThemeMode
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val gear1: Int = 6,
    val gear2: Int = 15,
    val gear3: Int = 20,
    val autoConnect: Boolean = true,
    val scooterName: String? = null,
    val scooterAddress: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val streamingUrl: String = "",
    val streamingEnabled: Boolean = false,
    val streamingToken: String = "",
    /** True wenn authentifiziert verbunden — Voraussetzung um Max-Speed zu senden. */
    val authenticated: Boolean = false,
    /** True wenn Roller parkt — Voraussetzung um Max-Speed *sicher* zu senden. */
    val parking: Boolean = false,
    /** Opt-in für GPS-Waypoints pro Trip, default OFF. */
    val trackLocation: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ble: BleRepository,
    private val store: SettingsStore,
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
            scooterName = s.lastScooterName,
            scooterAddress = s.lastScooterAddress,
            themeMode = s.themeMode,
            streamingUrl = s.streamingUrl.orEmpty(),
            streamingEnabled = s.streamingEnabled,
            streamingToken = s.streamingToken.orEmpty(),
            authenticated = (conn as? ConnectionState.Connected)?.authenticated == true,
            parking = tele?.parking == true,
            trackLocation = s.trackLocation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    val streamingStatus: StateFlow<TelemetryStreamer.Status> = streamer.status

    private val _pinStatus = MutableStateFlow<String?>(null)
    val pinStatus: StateFlow<String?> = _pinStatus.asStateFlow()

    init {
        viewModelScope.launch {
            ble.atReplies.collect { reply ->
                if (reply is AtResponse.PinChanged) _pinStatus.value = "PIN aktualisiert"
            }
        }
    }

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

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
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
}
