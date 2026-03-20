package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.usecase.ConnectCallUseCase
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import life.fxs.purr.domain.call.usecase.SelectAudioRouteUseCase
import life.fxs.purr.domain.call.usecase.ToggleMuteUseCase

@HiltViewModel
class CallViewModel @Inject constructor(
    private val prepareCallSessionUseCase: PrepareCallSessionUseCase,
    private val connectCallUseCase: ConnectCallUseCase,
    private val observeCallStateUseCase: ObserveCallStateUseCase,
    private val toggleMuteUseCase: ToggleMuteUseCase,
    private val selectAudioRouteUseCase: SelectAudioRouteUseCase,
    private val disconnectCallUseCase: DisconnectCallUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(CallState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CallEffect>()
    val effects = _effects.asSharedFlow()

    private var pendingPairId: String? = null

    init {
        viewModelScope.launch {
            observeCallStateUseCase().collect { session ->
                session?.let { updateState(it) }
            }
        }
    }

    fun onIntent(intent: CallIntent) {
        when (intent) {
            is CallIntent.ConnectCall -> requestMicrophonePermission(intent.pairId)
            CallIntent.AcceptOrResumeCall -> resumeOrReconnect()
            is CallIntent.MicrophonePermissionResult -> handleMicrophonePermissionResult(intent)
            CallIntent.MuteToggle -> toggleMute()
            is CallIntent.RouteSelect -> updateRoute(intent.route)
            CallIntent.EndCall -> endCall()
            CallIntent.RetryAfterReconnectFailure -> resumeOrReconnect()
        }
    }

    private fun requestMicrophonePermission(pairId: String) {
        pendingPairId = pairId
        viewModelScope.launch {
            _effects.emit(CallEffect.RequestMicrophonePermission)
        }
    }

    private fun handleMicrophonePermissionResult(result: CallIntent.MicrophonePermissionResult) {
        val pairId = pendingPairId ?: return
        pendingPairId = null
        if (result.granted) {
            connect(pairId)
            return
        }
        viewModelScope.launch {
            emitError(AppError.Validation("Microphone permission is required for calls"))
            if (result.permanentlyDenied) {
                _effects.emit(CallEffect.OpenAppSettings)
            }
        }
    }

    private fun connect(pairId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                screenState = CallScreenState.Dialing,
                isLoading = true,
            )
            when (val result = prepareCallSessionUseCase(PrepareCallParams(pairId = pairId))) {
                is AppResult.Success -> {
                    _state.value = _state.value.copy(
                        screenState = CallScreenState.Connecting,
                        isLoading = true,
                    )
                    handleActionResult(
                        result = connectCallUseCase(),
                        onFailure = {
                            _state.value = _state.value.copy(
                                screenState = CallScreenState.Ended,
                                isLoading = false,
                            )
                        },
                    )
                }
                is AppResult.Failure -> {
                    _state.value = _state.value.copy(
                        screenState = CallScreenState.Idle,
                        isLoading = false,
                    )
                    emitError(result.error)
                }
            }
        }
    }

    private fun resumeOrReconnect() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            handleActionResult(
                result = connectCallUseCase(),
                onFailure = {
                    _state.value = _state.value.copy(isLoading = false)
                },
            )
        }
    }

    private fun toggleMute() {
        viewModelScope.launch {
            val currentlyMuted = _state.value.localAudioState is LocalAudioState.Muted
            handleActionResult(toggleMuteUseCase(currentlyMuted))
        }
    }

    private fun updateRoute(route: AudioRoute) {
        viewModelScope.launch {
            handleActionResult(selectAudioRouteUseCase(route))
        }
    }

    private fun endCall() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                screenState = CallScreenState.Ending,
                isLoading = true,
            )
            handleActionResult(
                result = disconnectCallUseCase(),
                onFailure = {
                    _state.value = _state.value.copy(isLoading = false)
                },
            )
        }
    }

    private suspend fun handleActionResult(
        result: AppResult<Unit>,
        onFailure: (AppError) -> Unit = {},
    ) {
        when (result) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> {
                onFailure(result.error)
                emitError(result.error)
            }
        }
    }

    private suspend fun emitError(error: AppError) {
        _effects.emit(CallEffect.ShowMessage(error.toMessage()))
    }

    private fun updateState(session: CallSession) {
        _state.value = CallState(
            screenState = session.connectionState.toScreenState(),
            session = session,
            localAudioState = session.localAudioState,
            availableRoutes = session.uiSnapshot.availableAudioRoutes,
            activeRoute = session.uiSnapshot.activeAudioRoute,
            recordingState = session.recordingState,
            isForegroundServiceActive = session.uiSnapshot.isForegroundServiceActive,
            isLoading = false,
        )
    }
}

private fun CallConnectionState.toScreenState(): CallScreenState = when (this) {
    CallConnectionState.Idle -> CallScreenState.Idle
    CallConnectionState.Preparing -> CallScreenState.Dialing
    CallConnectionState.Connecting -> CallScreenState.Connecting
    CallConnectionState.Connected -> CallScreenState.Active
    CallConnectionState.Reconnecting -> CallScreenState.Reconnecting
    CallConnectionState.Disconnected -> CallScreenState.Ended
    is CallConnectionState.Failed -> CallScreenState.Ended
}

private fun AppError.toMessage(): String = when (this) {
    is AppError.Network -> message ?: "Network error"
    is AppError.Unauthorized -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "Unexpected error"
}
