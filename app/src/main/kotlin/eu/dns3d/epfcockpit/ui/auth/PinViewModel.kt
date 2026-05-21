package eu.dns3d.epfcockpit.ui.auth

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PinUiState(
    val pin: String = "",
    val remember: Boolean = true,
    val submitting: Boolean = false,
    val rejected: Boolean = false,
    val ok: Boolean = false,
)

@HiltViewModel
class PinViewModel @Inject constructor(
    private val ble: BleRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PinUiState())
    val state: StateFlow<PinUiState> = _state.asStateFlow()

    val connection = ble.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ble.connection.value)

    init {
        viewModelScope.launch {
            val saved = settings.flow.first().savedPin
            if (saved != null) {
                _state.update { it.copy(pin = saved) }
                submit()
            }
        }
        viewModelScope.launch {
            ble.atReplies.collect { reply ->
                when (reply) {
                    AtResponse.PinOk -> {
                        _state.update { it.copy(submitting = false, ok = true, rejected = false) }
                        if (_state.value.remember) settings.savePin(_state.value.pin)
                    }
                    AtResponse.PinRejected -> {
                        _state.update { it.copy(submitting = false, rejected = true) }
                        settings.clearPin()
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onPinChange(value: String) {
        if (value.length > 6 || value.any { !it.isDigit() }) return
        _state.update { it.copy(pin = value, rejected = false) }
    }

    fun onRememberChange(v: Boolean) {
        _state.update { it.copy(remember = v) }
    }

    fun submit() {
        if (_state.value.pin.length != 6) return
        _state.update { it.copy(submitting = true, rejected = false) }
        viewModelScope.launch { ble.authenticate(_state.value.pin) }
    }
}
