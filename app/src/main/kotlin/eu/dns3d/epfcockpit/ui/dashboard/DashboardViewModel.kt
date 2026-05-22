package eu.dns3d.epfcockpit.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RideMode { Stand, Kickstart }

data class DashboardUiState(
    val telemetry: Telemetry? = null,
    /**
     * Local parking state — seeded once from the first telemetry frame after
     * connect (where Bit 5 reliably reflects the physical park lock), then
     * updated ONLY via user taps on the Parking switch. We can't keep
     * mirroring the telemetry bit live because Mode frames (A2/82) also
     * flip Bit 5 even though the physical park lock doesn't actually move
     * with mode changes — that would make the switch jump around for the
     * wrong reason.
     */
    val parking: Boolean = false,
    /**
     * Ride mode is NOT reflected in telemetry — tracked purely client-side.
     */
    val rideMode: RideMode = RideMode.Stand,
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

    /**
     * Block control toggles while the scooter is moving so the rider can't
     * accidentally tap Parking / Mode / Blinkerton mid-ride. 1.0 km/h gives
     * a small noise margin around standstill.
     */
    val controlsLocked: StateFlow<Boolean> = ble.telemetry
        .map { (it?.speedKmh ?: 0f) > 1.0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var initialParkingSeeded = false

    init {
        // Seed last ride-mode from persistent storage so the segmented
        // button doesn't snap back to "Stand" on every app start.
        viewModelScope.launch {
            val snap = settings.flow.first()
            val mode = if (snap.rideModeStand) RideMode.Stand else RideMode.Kickstart
            _state.update { it.copy(rideMode = mode) }
        }
        viewModelScope.launch {
            ble.telemetry.filterNotNull().collect { telemetry ->
                _state.update {
                    val parking = if (!initialParkingSeeded) {
                        initialParkingSeeded = true
                        telemetry.parking
                    } else it.parking
                    it.copy(telemetry = telemetry, parking = parking)
                }
            }
        }
        // Trip-Recording wandert in TripCoordinator (@Singleton) — überlebt
        // Screen-Navigation und FGS-Backgrounding. Diese VM kümmert sich nur
        // um UI-State.
    }

    fun toggleParking() {
        val next = !_state.value.parking
        _state.update { it.copy(parking = next) }
        ble.setParking(next)
    }

    fun setRideMode(mode: RideMode) {
        _state.update { it.copy(rideMode = mode) }
        ble.setRideMode(stand = mode == RideMode.Stand)
        viewModelScope.launch { settings.setRideModeStand(mode == RideMode.Stand) }
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
