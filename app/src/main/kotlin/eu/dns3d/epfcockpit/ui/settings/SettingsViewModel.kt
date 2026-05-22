package eu.dns3d.epfcockpit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.dns3d.epfcockpit.ble.AtResponse
import eu.dns3d.epfcockpit.ble.BleRepository
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ble: BleRepository,
    private val store: SettingsStore,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = store.flow
        .map { s ->
            SettingsUiState(
                gear1 = s.gear1MaxKmh,
                gear2 = s.gear2MaxKmh,
                gear3 = s.gear3MaxKmh,
                autoConnect = s.autoConnect,
                scooterName = s.lastScooterName,
                scooterAddress = s.lastScooterAddress,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

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

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { store.setAutoConnect(enabled) }
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
