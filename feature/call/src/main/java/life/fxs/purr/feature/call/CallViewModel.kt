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
import life.fxs.purr.domain.call.usecase.LoadCallRecordingsUseCase
import life.fxs.purr.domain.call.usecase.CreateRecordingDownloadUseCase

@HiltViewModel
class CallViewModel @Inject constructor(
    private val prepareCallSessionUseCase: PrepareCallSessionUseCase,
    private val connectCallUseCase: ConnectCallUseCase,
    private val observeCallStateUseCase: ObserveCallStateUseCase,
    private val toggleMuteUseCase: ToggleMuteUseCase,
    private val selectAudioRouteUseCase: SelectAudioRouteUseCase,
    private val disconnectCallUseCase: DisconnectCallUseCase,
    private val loadCallRecordingsUseCase: LoadCallRecordingsUseCase,
    private val createRecordingDownloadUseCase: CreateRecordingDownloadUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(CallState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CallEffect>()
    val effects = _effects.asSharedFlow()

    private var pendingPairId: String? = null
    private var recordingsLoadedForCallId: String? = null

    init {
        viewModelScope.launch {
            observeCallStateUseCase().collect { session ->
                session?.let {
                    val shouldLoadRecordings = it.connectionState == CallConnectionState.Disconnected &&
                        recordingsLoadedForCallId != it.callId
                    updateState(it)
                    if (shouldLoadRecordings) {
                        recordingsLoadedForCallId = it.callId
                        loadRecordings()
                    }
                }
            }
        }
    }

    fun onIntent(intent: CallIntent) {
        when (intent) {
            is CallIntent.ConnectCall -> requestRecordingConsent(intent.pairId)
            CallIntent.AcceptOrResumeCall -> resumeOrReconnect()
            is CallIntent.RecordingConsentResult -> handleRecordingConsentResult(intent.granted)
            is CallIntent.MicrophonePermissionResult -> handleMicrophonePermissionResult(intent)
            CallIntent.MuteToggle -> toggleMute()
            is CallIntent.RouteSelect -> updateRoute(intent.route)
            CallIntent.EndCall -> endCall()
            CallIntent.RetryAfterReconnectFailure -> resumeOrReconnect()
            CallIntent.RefreshRecordings -> loadRecordings()
            is CallIntent.PlayRecording -> playRecording(intent.recordingId)
            is CallIntent.RecordingPlaybackStarted -> {
                _state.value = _state.value.copy(
                    playbackLoadingRecordingId = null,
                    playingRecordingId = intent.recordingId,
                    recordingsError = null,
                )
            }
            is CallIntent.RecordingPlaybackStopped -> if (_state.value.playingRecordingId == intent.recordingId) {
                _state.value = _state.value.copy(playingRecordingId = null)
            }
            is CallIntent.RecordingPlaybackFailed -> {
                _state.value = _state.value.copy(
                    playbackLoadingRecordingId = null,
                    playingRecordingId = null,
                    recordingsError = intent.reason ?: "录音播放失败",
                )
            }
        }
    }

    private fun requestRecordingConsent(pairId: String) {
        pendingPairId = pairId
        _state.value = _state.value.copy(recordingConsentRequired = true)
    }

    private fun handleRecordingConsentResult(granted: Boolean) {
        _state.value = _state.value.copy(recordingConsentRequired = false)
        if (!granted) {
            pendingPairId = null
            viewModelScope.launch {
                _effects.emit(CallEffect.ShowMessage("未同意录音，无法加入通话"))
            }
            return
        }
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
            emitError(AppError.Validation("通话需要麦克风权限"))
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
            when (val result = prepareCallSessionUseCase(
                PrepareCallParams(pairId = pairId, recordingConsent = true),
            )) {
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

    private fun loadRecordings() {
        val callId = _state.value.session?.callId ?: return
        if (_state.value.isRecordingsLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isRecordingsLoading = true, recordingsError = null)
            when (val result = loadCallRecordingsUseCase(callId)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    recordings = result.value,
                    isRecordingsLoading = false,
                )
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isRecordingsLoading = false,
                    recordingsError = result.error.toUserMessage(),
                )
            }
        }
    }

    private fun playRecording(recordingId: String) {
        val callId = _state.value.session?.callId ?: return
        if (_state.value.playingRecordingId == recordingId) {
            _state.value = _state.value.copy(playingRecordingId = null)
            viewModelScope.launch { _effects.emit(CallEffect.PauseRecording) }
            return
        }
        if (_state.value.playbackLoadingRecordingId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                playbackLoadingRecordingId = recordingId,
                recordingsError = null,
            )
            when (val result = createRecordingDownloadUseCase(callId, recordingId)) {
                is AppResult.Success -> _effects.emit(
                    CallEffect.PlayRecording(recordingId, result.value.url),
                )
                is AppResult.Failure -> _state.value = _state.value.copy(
                    playbackLoadingRecordingId = null,
                    recordingsError = result.error.toUserMessage(),
                )
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

    private fun updateState(session: CallSession) {
        val previous = _state.value
        val sameCall = previous.session?.callId == session.callId
        _state.value = CallState(
            screenState = session.connectionState.toScreenState(),
            session = session,
            localAudioState = session.localAudioState,
            availableRoutes = session.uiSnapshot.availableAudioRoutes,
            activeRoute = session.uiSnapshot.activeAudioRoute,
            recordingState = session.recordingState,
            isForegroundServiceActive = session.uiSnapshot.isForegroundServiceActive,
            isLoading = false,
            recordingConsentRequired = previous.recordingConsentRequired,
            recordings = previous.recordings.takeIf { sameCall }.orEmpty(),
            isRecordingsLoading = previous.isRecordingsLoading && sameCall,
            playbackLoadingRecordingId = previous.playbackLoadingRecordingId.takeIf { sameCall },
            playingRecordingId = previous.playingRecordingId.takeIf { sameCall },
            recordingsError = previous.recordingsError.takeIf { sameCall },
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
