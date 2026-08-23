package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.isEffectivelyMuted
import life.fxs.purr.domain.call.usecase.ConnectCallUseCase
import life.fxs.purr.domain.call.usecase.CancelCallPreparationUseCase
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import life.fxs.purr.domain.call.usecase.SelectAudioRouteUseCase
import life.fxs.purr.domain.call.usecase.ToggleMuteUseCase
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import life.fxs.purr.domain.incomingcall.PrepareIncomingCallUseCase

@HiltViewModel
class CallViewModel @Inject constructor(
    private val prepareCallSessionUseCase: PrepareCallSessionUseCase,
    private val cancelCallPreparationUseCase: CancelCallPreparationUseCase,
    private val prepareIncomingCallUseCase: PrepareIncomingCallUseCase,
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
    private var hasNavigatedHome: Boolean = false
    private val locallyEndedCallIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            observeCallStateUseCase().collect { session ->
                if (session != null) onSessionChanged(session)
            }
        }
    }

    fun onIntent(intent: CallIntent) {
        when (intent) {
            is CallIntent.StartNewOutgoingCall -> startNewOutgoingCall(
                pairId = intent.pairId,
                remoteDisplayName = intent.remoteDisplayName,
            )
            is CallIntent.OpenExistingCall -> openExistingCall(
                pairId = intent.pairId,
                remoteDisplayName = intent.remoteDisplayName,
                direction = intent.direction,
                callId = intent.callId,
            )
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

    private fun startNewOutgoingCall(
        pairId: String,
        remoteDisplayName: String,
    ) {
        startCall(
            request = CallPreparationRequest.NewOutgoing(
                pairId = pairId,
                recordingConsent = true,
                remoteDisplayName = remoteDisplayName,
            ),
        )
    }

    private fun openExistingCall(
        pairId: String,
        remoteDisplayName: String,
        direction: CallDirection,
        callId: String,
    ) {
        if (callId in locallyEndedCallIds) return
        startCall(
            request = CallPreparationRequest.Existing(
                pairId = pairId,
                callId = callId,
                remoteDisplayName = remoteDisplayName,
                direction = direction,
                recordingConsent = true,
            ),
        )
    }

    private fun startCall(request: CallPreparationRequest) {
        prepareJob?.cancel()
        connectJob?.cancel()
        endRequested = false
        currentCallId = (request as? CallPreparationRequest.Existing)?.callId
        hasNavigatedHome = false
        prepareJob = viewModelScope.launch {
            val existingSession = observeCallStateUseCase().first()
            if (
                request is CallPreparationRequest.Existing &&
                existingSession?.callId == request.callId &&
                existingSession.connectionState.isResumable
            ) {
                currentCallId = existingSession.callId
                updateState(existingSession)
                if (existingSession.connectionState == CallConnectionState.Preparing) {
                    _effects.emit(CallEffect.RequestMicrophonePermission)
                }
                return@launch
            }
            _state.value = _state.value.copy(
                screenState = CallScreenState.Dialing,
                session = null,
                isLoading = true,
                failureMessage = null,
            )
            val preparation = when (request) {
                is CallPreparationRequest.NewOutgoing -> prepareCallSessionUseCase(request)
                is CallPreparationRequest.Existing -> prepareIncomingCallUseCase(request)
            }
            when (val result = preparation) {
                is AppResult.Success -> {
                    if (endRequested) return@launch
                    currentCallId = result.value.callId
                    updateState(result.value)
                    _effects.emit(CallEffect.RequestMicrophonePermission)
                }
                is AppResult.Failure -> {
                    val message = result.error.toCallUserMessage(CALL_PREPARATION_FAILED_MESSAGE)
                    _state.value = _state.value.copy(
                        screenState = CallScreenState.Failed,
                        isLoading = false,
                        failureMessage = message,
                    )
                    _effects.emit(CallEffect.ShowMessage(message))
                    navigateHomeOnce()
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
            when (val result = connectCallUseCase()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> {
                    val message = result.error.toCallUserMessage(CALL_CONNECTION_FAILED_MESSAGE)
                    _state.value = _state.value.copy(
                        screenState = CallScreenState.Failed,
                        isLoading = false,
                        failureMessage = message,
                    )
                    _effects.emit(CallEffect.ShowMessage(message))
                    endCall()
                }
            }
        }
    }

    private fun toggleMute() {
        viewModelScope.launch {
            val currentlyMuted = _state.value.localAudioState.isEffectivelyMuted
            handleActionResult(toggleMuteUseCase(currentlyMuted))
        }
    }

    private fun updateRoute(route: AudioRoute) {
        viewModelScope.launch {
            handleActionResult(selectAudioRouteUseCase(route))
        }
    }

    private fun endCall() {
        if (endRequested) return
        endRequested = true
        val endedCallId = currentCallId
        endedCallId?.let(locallyEndedCallIds::add)
        prepareJob?.cancel()
        prepareJob = null
        connectJob?.cancel()
        connectJob = null
        _state.value = _state.value.copy(
            screenState = CallScreenState.Ended,
            isLoading = false,
            failureMessage = null,
        )
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Navigation destroys this ViewModel, but termination is application-owned.
            // Commit the presentation boundary and the callId handoff in one
            // non-cancellable section so the repository always receives the request.
            withContext(NonCancellable) {
                navigateHomeOnce()
                cancelCallPreparationUseCase()
                endedCallId?.let { disconnectCallUseCase(it) }
            }
        }
    }

    private suspend fun handleActionResult(
        result: AppResult<Unit>,
        fallbackMessage: String = CALL_ACTION_FAILED_MESSAGE,
        onFailure: (String) -> Unit = {},
    ) {
        when (result) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> {
                val message = result.error.toCallUserMessage(fallbackMessage)
                onFailure(message)
                _effects.emit(CallEffect.ShowMessage(message))
            }
        }
    }

    private suspend fun emitError(
        error: AppError,
        fallbackMessage: String = CALL_ACTION_FAILED_MESSAGE,
    ) {
        _effects.emit(CallEffect.ShowMessage(error.toCallUserMessage(fallbackMessage)))
    }

    private suspend fun onSessionChanged(session: CallSession) {
        if (session.callId in locallyEndedCallIds) return
        if (currentCallId != null && session.callId != currentCallId) return

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
        if (!endRequested && session.connectionState.isTerminal) {
            navigateHomeOnce()
        }
    }

    private suspend fun navigateHomeOnce() {
        if (hasNavigatedHome) return
        hasNavigatedHome = true
        _effects.emit(CallEffect.NavigateHome)
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
            failureMessage = if (screenState == CallScreenState.Failed) {
                _state.value.failureMessage ?: CALL_CONNECTION_FAILED_MESSAGE
            } else {
                null
            },
        )
    }

    private companion object {
        const val CALL_PREPARATION_FAILED_MESSAGE = "通话准备失败，请稍后重试"
        const val CALL_CONNECTION_FAILED_MESSAGE = "通话连接失败，请稍后重试"
        const val CALL_ACTION_FAILED_MESSAGE = "通话操作失败，请稍后重试"
    }
}

private fun AppError.toCallUserMessage(fallbackMessage: String): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Unexpected -> fallbackMessage
    else -> toUserMessage().takeIf(String::isNotBlank) ?: fallbackMessage
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
    is CallConnectionState.Failed -> CallScreenState.Failed
}
