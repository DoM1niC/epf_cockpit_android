package eu.dns3d.epfcockpit.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.ControlState
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.data.SettingsStore
import eu.dns3d.epfcockpit.data.TripDao
import eu.dns3d.epfcockpit.data.TripRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val telemetry: Telemetry? = null,
    val control: ControlState = ControlState(),
    val gear1MaxKmh: Int = 6,
    val gear2MaxKmh: Int = 15,
    val gear3MaxKmh: Int = 20,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val ble: BleRepository,
    private val settings: SettingsStore,
    private val tripDao: TripDao,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    val connection: StateFlow<ConnectionState> = ble.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ble.connection.value)

    private var recorder: TripRecorder? = null

    init {
        viewModelScope.launch {
            settings.flow.collect { s ->
                _state.update {
                    it.copy(
                        gear1MaxKmh = s.gear1MaxKmh,
                        gear2MaxKmh = s.gear2MaxKmh,
                        gear3MaxKmh = s.gear3MaxKmh,
                        control = it.control.copy(
                            gear1MaxKmh = s.gear1MaxKmh,
                            gear2MaxKmh = s.gear2MaxKmh,
                            gear3MaxKmh = s.gear3MaxKmh,
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(ble.telemetry.filterNotNull(), ble.connection) { t, c -> t to c }
                .collect { (telemetry, conn) ->
                    _state.update { it.copy(telemetry = telemetry) }

                    if (conn is ConnectionState.Connected) {
                        val rec = recorder ?: TripRecorder(conn.address).also { recorder = it }
                        rec.onSample(telemetry, System.currentTimeMillis())
                    }
                }
        }
    }

    fun toggleLight() = updateControl { it.copy(lightOn = !it.lightOn) }
    fun toggleLock() = updateControl { it.copy(locked = !it.locked) }
    fun setGear(gear: Int) = updateControl { it.copy(gear = gear.coerceIn(1, 3)) }

    fun resetTrip() {
        viewModelScope.launch {
            val current = _state.value.control
            ble.sendControl(current.copy(resetTrip = true))
            // Clear flag right after so it's a one-shot
            ble.sendControl(current.copy(resetTrip = false))
            _state.update { it.copy(control = current.copy(resetTrip = false)) }
        }
    }

    private fun updateControl(transform: (ControlState) -> ControlState) {
        val next = transform(_state.value.control)
        _state.update { it.copy(control = next) }
        viewModelScope.launch { ble.sendControl(next) }
    }

    fun disconnect() {
        viewModelScope.launch {
            recorder?.finalize()?.let { tripDao.insert(it) }
            recorder = null
            ble.disconnect()
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            recorder?.finalize()?.let { tripDao.insert(it) }
        }
    }
}
