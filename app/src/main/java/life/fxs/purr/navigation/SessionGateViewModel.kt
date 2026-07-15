package life.fxs.purr.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.incomingcall.ObservePresentableIncomingCallUseCase
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.IncomingCall

@HiltViewModel
class SessionGateViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    observePairBondUseCase: ObservePairBondUseCase,
    observePresentableIncomingCallUseCase: ObservePresentableIncomingCallUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionGateState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                observeAuthSessionUseCase(),
                observePairBondUseCase(),
                observePresentableIncomingCallUseCase(),
            ) { session, bond, incomingCall ->
                SessionGateState(
                    isReady = true,
                    isAuthenticated = session != null,
                    self = session?.self,
                    partner = bond?.partner,
                    incomingCall = incomingCall.takeIf { session != null },
                )
            }.collect { combinedState ->
                _state.value = combinedState
            }
        }
    }
}

data class SessionGateState(
    val isReady: Boolean = false,
    val isAuthenticated: Boolean = false,
    val self: SelfProfile? = null,
    val partner: PairedPartner? = null,
    val incomingCall: IncomingCall? = null,
)
