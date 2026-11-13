package eu.dns3d.epfcockpit.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.data.DashboardLayout
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RideMode { Stand, Kickstart }

data class DashboardUiState(
    val telemetry: Telemetry? = null,
    /**
     * Intent-basierter Park-Zustand aus [BleRepository.parkingDisplay].
     * `null` bis zum ersten Telemetrie-Frame / nach Disconnect.
     */
    val parking: Boolean? = null,
    /**
     * Ride mode is NOT reflected in telemetry — tracked purely client-side.
     */
    val rideMode: RideMode = RideMode.Stand,
    /** Vom User gewählte Layout-Variante; live aus dem SettingsStore. */
    val dashboardLayout: DashboardLayout = DashboardLayout.STANDARD,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val ble: BleRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())

    val state: StateFlow<DashboardUiState> = _state
        .stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUiState())

    val connection: StateFlow<ConnectionState> = ble.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ble.connection.value)

    /** Blinkerton-off state from the scooter; `null` until we get the first reply. */
    val blinkertonOff: StateFlow<Boolean?> = ble.voiceOff

    /** Frontlicht; `null` bis zur ersten Telemetrie / nach Disconnect. */
    val lightOn: StateFlow<Boolean?> = ble.lightOn

    /**
     * Block control toggles while the scooter is moving so the rider can't
     * accidentally tap Parking / Mode / Blinkerton mid-ride. 1.0 km/h gives
     * a small noise margin around standstill.
     */
    val controlsLocked: StateFlow<Boolean> = ble.telemetry
        .map { (it?.speedKmh ?: 0f) > 1.0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Ride-Mode live aus dem SettingsStore beobachten — so spiegelt der
        // Segmented Button auch Aenderungen vom Widget / Quick-Tile / einem
        // zweiten Phone wider, nicht nur den Initialwert beim App-Start.
        viewModelScope.launch {
            settings.flow.map { it.rideModeStand }.distinctUntilChanged().collect { stand ->
                val mode = if (stand) RideMode.Stand else RideMode.Kickstart
                _state.update { it.copy(rideMode = mode) }
            }
        }
        // Layout-Variante live beobachten — Wechsel in den Einstellungen spiegelt
        // sich sofort im Dashboard wider.
        viewModelScope.launch {
            settings.flow.map { it.dashboardLayout }.distinctUntilChanged().collect { layout ->
                _state.update { it.copy(dashboardLayout = layout) }
            }
        }
        // Telemetrie nur zur Anzeige durchreichen — der Park-Zustand kommt jetzt
        // ausschliesslich aus dem ParkController (verifiziert), nicht mehr aus
        // einem einmalig geseedeten lokalen Bit.
        viewModelScope.launch {
            ble.telemetry.collect { t -> _state.update { it.copy(telemetry = t) } }
        }
        // Intent-basierter Park-Zustand. parkingDisplay ist die einzige Quelle
        // die alle Oberflächen (Dashboard, Widget, Quick-Tile, Service) anzeigen,
        // weil alle durch denselben ParkController laufen.
        viewModelScope.launch {
            ble.parkingDisplay.collect { disp -> _state.update { it.copy(parking = disp) } }
        }
        // Trip-Recording wandert in TripCoordinator (@Singleton) — überlebt
        // Screen-Navigation und FGS-Backgrounding. Diese VM kümmert sich nur
        // um UI-State.
    }

    fun toggleParking() {
        // Den ECHTEN/Ziel-Zustand invertieren, nicht eine eingefrorene lokale
        // Kopie — das allein killt schon den Doppel-Tap. Den Rest (Verify +
        // Retry + Anzeige) macht der ParkController.
        val base = ble.parkingDisplay.value ?: false
        ble.setParking(!base)
    }

    fun setRideMode(mode: RideMode) {
        _state.update { it.copy(rideMode = mode) }
        ble.setRideMode(stand = mode == RideMode.Stand)
        viewModelScope.launch { settings.setRideModeStand(mode == RideMode.Stand) }
    }

    fun toggleLight() {
        ble.setLight(!(ble.lightOn.value ?: false))
    }

    fun toggleBlinkerton() {
        val next = !(ble.voiceOff.value ?: false)
        ble.setBlinkerton(next)
    }

    fun disconnect() {
        // TripCoordinator finalisiert + persistiert die Fahrt, sobald
        // BleRepository.connection auf Disconnected umspringt.
        ble.disconnect()
    }
}
