package life.fxs.purr.feature.incomingcall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase
import life.fxs.purr.domain.incomingcall.ObservePresentableIncomingCallUseCase

@HiltViewModel
internal class IncomingCallPromptViewModel @Inject constructor(
    observePresentableIncomingCall: ObservePresentableIncomingCallUseCase,
    private val declineIncomingCall: DeclineIncomingCallUseCase,
) : ViewModel() {
    private val responding = MutableStateFlow(false)
    private val effectsFlow = MutableSharedFlow<IncomingCallPromptEffect>()
    private var responseJob: Job? = null

    val effects = effectsFlow.asSharedFlow()
    val state = combine(observePresentableIncomingCall(), responding) { call, isResponding ->
        IncomingCallPromptState(
            call = call,
            isResponding = isResponding,
            isReady = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IncomingCallPromptState(),
    )

    fun onIntent(intent: IncomingCallPromptIntent) {
        if (responseJob?.isActive == true) return
        val call = state.value.call ?: return

        responseJob = viewModelScope.launch {
            responding.value = true
            try {
                when (intent) {
                    IncomingCallPromptIntent.Accept -> {
                        effectsFlow.emit(
                            IncomingCallPromptEffect.NavigateToCall(
                                pairId = call.pairId,
                                callId = call.callId,
                            ),
                        )
                    }
                    IncomingCallPromptIntent.Decline -> when (val result = declineIncomingCall(call.callId)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> {
                            effectsFlow.emit(IncomingCallPromptEffect.ShowError(result.error.toUserMessage()))
                        }
                    }
                }
            } finally {
                responding.value = false
            }
        }
    }
}
