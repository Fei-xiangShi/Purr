package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.presentation.toUserMessage
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
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider

@HiltViewModel
class CallViewModel @Inject constructor(
    private val prepareCallSessionUseCase: PrepareCallSessionUseCase,
    private val connectCallUseCase: ConnectCallUseCase,
    private val observeCallStateUseCase: ObserveCallStateUseCase,
    private val toggleMuteUseCase: ToggleMuteUseCase,
    private val selectAudioRouteUseCase: SelectAudioRouteUseCase,
    private val disconnectCallUseCase: DisconnectCallUseCase,
    audioLevelProvider: CallAudioLevelProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(CallState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CallEffect>()
    val effects = _effects.asSharedFlow()
    val localAudioLevel: StateFlow<Float> = audioLevelProvider.localAudioLevel
    val remoteAudioLevel: StateFlow<Float> = audioLevelProvider.remoteAudioLevel

    private var prepareJob: Job? = null
    private var connectJob: Job? = null
    private var endRequested: Boolean = false
    private var currentCallId: String? = null
    private var navigatedCallId: String? = null

    init {
        viewModelScope.launch {
            observeCallStateUseCase().collect { session ->
                if (session != null) onSessionChanged(session)
            }
        }
    }

    fun onIntent(intent: CallIntent) {
        when (intent) {
            is CallIntent.ConnectCall -> startCall(intent.pairId)
            is CallIntent.MicrophonePermissionResult -> handleMicrophonePermissionResult(intent)
            CallIntent.MuteToggle -> toggleMute()
            is CallIntent.RouteSelect -> updateRoute(intent.route)
            CallIntent.EndCall -> endCall()
        }
    }

    private fun handleMicrophonePermissionResult(result: CallIntent.MicrophonePermissionResult) {
        if (endRequested || _state.value.session == null) return
        if (result.granted) {
            connectPreparedCall()
            return
        }
        viewModelScope.launch {
            emitError(AppError.Validation("通话需要麦克风权限"))
            if (result.permanentlyDenied) {
                _effects.emit(CallEffect.OpenAppSettings)
            }
        }
        endCall()
    }

    private fun startCall(pairId: String) {
        prepareJob?.cancel()
        connectJob?.cancel()
        endRequested = false
        currentCallId = null
        navigatedCallId = null
        prepareJob = viewModelScope.launch {
            val existingSession = observeCallStateUseCase().first()
            if (existingSession?.connectionState?.isOngoing == true) {
                currentCallId = existingSession.callId
                updateState(existingSession)
                if (existingSession.connectionState == CallConnectionState.Preparing) {
                    _effects.emit(CallEffect.RequestMicrophonePermission)
                }
                return@launch
            }
            _state.value = _state.value.copy(
                screenState = CallScreenState.Dialing,
                isLoading = true,
            )
            when (val result = prepareCallSessionUseCase(
                PrepareCallParams(pairId = pairId, recordingConsent = true),
            )) {
                is AppResult.Success -> {
                    if (endRequested) return@launch
                    currentCallId = result.value.callId
                    updateState(result.value)
                    _effects.emit(CallEffect.RequestMicrophonePermission)
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

    private fun connectPreparedCall() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            if (endRequested) return@launch
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
        if (_state.value.screenState == CallScreenState.Ending) return
        endRequested = true
        val inFlightPrepare = prepareJob
        prepareJob = null
        val inFlightConnect = connectJob
        connectJob = null
        _state.value = _state.value.copy(
            screenState = CallScreenState.Ending,
            isLoading = true,
        )
        viewModelScope.launch {
            inFlightPrepare?.join()
            inFlightConnect?.cancelAndJoin()
            when (val result = disconnectCallUseCase()) {
                is AppResult.Success -> {
                    endRequested = false
                    _state.value = _state.value.copy(
                        screenState = CallScreenState.Ended,
                        isLoading = false,
                    )
                    navigateHomeOnce(currentCallId)
                }
                is AppResult.Failure -> {
                    endRequested = false
                    _state.value = _state.value.copy(
                        screenState = CallScreenState.Ended,
                        isLoading = false,
                    )
                    emitError(result.error)
                    navigateHomeOnce(currentCallId)
                }
            }
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
        _effects.emit(CallEffect.ShowMessage(error.toUserMessage()))
    }

    private suspend fun onSessionChanged(session: CallSession) {
        val isTerminal = session.connectionState.isTerminal
        if (!isTerminal) {
            currentCallId = session.callId
            updateState(session)
            return
        }

        // A singleton repository intentionally retains the last terminal snapshot.
        // A newly-created screen must not navigate away because of that stale snapshot.
        if (session.callId != currentCallId) return
        updateState(session)
        if (!endRequested) navigateHomeOnce(session.callId)
    }

    private suspend fun navigateHomeOnce(callId: String?) {
        callId ?: return
        if (navigatedCallId == callId) return
        navigatedCallId = callId
        _effects.emit(CallEffect.NavigateHome(callId))
    }

    private fun updateState(session: CallSession) {
        val screenState = if (endRequested) {
            CallScreenState.Ending
        } else {
            session.toScreenState()
        }
        _state.value = CallState(
            screenState = screenState,
            session = session,
            localAudioState = session.localAudioState,
            availableRoutes = session.uiSnapshot.availableAudioRoutes,
            activeRoute = session.uiSnapshot.activeAudioRoute,
            recordingState = session.recordingState,
            isForegroundServiceActive = session.uiSnapshot.isForegroundServiceActive,
            isLoading = screenState == CallScreenState.Ending,
        )
    }
}

private fun CallSession.toScreenState(): CallScreenState = when (connectionState) {
    CallConnectionState.Idle -> CallScreenState.Idle
    CallConnectionState.Preparing -> CallScreenState.Dialing
    CallConnectionState.Connecting -> CallScreenState.Connecting
    CallConnectionState.Connected -> if (uiSnapshot.remoteParticipantConnected) {
        CallScreenState.Active
    } else {
        CallScreenState.Waiting
    }
    CallConnectionState.Reconnecting -> CallScreenState.Reconnecting
    CallConnectionState.Terminating -> CallScreenState.Ending
    CallConnectionState.Disconnected -> CallScreenState.Ended
    is CallConnectionState.Failed -> CallScreenState.Ended
}
