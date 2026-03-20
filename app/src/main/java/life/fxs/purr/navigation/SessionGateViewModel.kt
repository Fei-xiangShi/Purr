package life.fxs.purr.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase

@HiltViewModel
class SessionGateViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionGateState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeAuthSessionUseCase().collect { session ->
                _state.value = SessionGateState(
                    isReady = true,
                    isAuthenticated = session != null,
                )
            }
        }
    }
}

data class SessionGateState(
    val isReady: Boolean = false,
    val isAuthenticated: Boolean = false,
)
