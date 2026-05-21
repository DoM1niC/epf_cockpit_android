package eu.dns3d.epfcockpit.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.DiscoveredScooter
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredScooter> = emptyList(),
    val rememberedAddress: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val ble: BleRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    val connection: StateFlow<ConnectionState> = ble.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val snap = settings.flow.first()
            _state.update { it.copy(rememberedAddress = snap.lastScooterAddress) }
            if (snap.autoConnect && snap.lastScooterAddress != null) {
                ble.connect(snap.lastScooterAddress)
            } else {
                startScan()
            }
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        scanJob = viewModelScope.launch {
            runCatching {
                ble.scan().collect { found ->
                    _state.update { current ->
                        val deduped = (current.devices.filterNot { it.address == found.address } + found)
                            .sortedByDescending { it.rssi }
                        current.copy(devices = deduped)
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(scanning = false, error = e.message) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun connect(device: DiscoveredScooter) {
        viewModelScope.launch {
            stopScan()
            settings.rememberScooter(device.address, device.name)
            ble.connect(device.address)
        }
    }
}
