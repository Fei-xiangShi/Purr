package life.fxs.purr.feature.call

import android.app.Activity
import android.content.Context
import android.view.View
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.media.screenshare.ScreenCapturePermission
import life.fxs.purr.core.media.screenshare.ScreenSharePublishRequest
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherController
import life.fxs.purr.core.media.screenshare.WhepPlaybackController
import life.fxs.purr.core.media.screenshare.WhepPlaybackRequest
import life.fxs.purr.core.media.screenshare.WhepPlaybackStatus
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalCallInterruption
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ScreenShareSnapshot
import life.fxs.purr.domain.call.model.ScreenShareSession
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.model.ScreenShareStatus
import life.fxs.purr.domain.call.model.RemoteCallInterruption
import life.fxs.purr.domain.call.model.isEffectivelyMuted
import life.fxs.purr.domain.call.usecase.ConnectCallUseCase
import life.fxs.purr.domain.call.usecase.CancelCallPreparationUseCase
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import life.fxs.purr.domain.call.usecase.SelectAudioRouteUseCase
import life.fxs.purr.domain.call.usecase.ToggleMuteUseCase
import life.fxs.purr.domain.call.usecase.CreateScreenShareUseCase
import life.fxs.purr.domain.call.usecase.ObserveScreenShareUseCase
import life.fxs.purr.domain.call.usecase.StopScreenShareUseCase
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
    private val observeScreenShareUseCase: ObserveScreenShareUseCase,
    private val createScreenShareUseCase: CreateScreenShareUseCase,
    private val stopScreenShareUseCase: StopScreenShareUseCase,
    private val screenSharePublisherController: ScreenSharePublisherController,
    private val whepPlaybackController: WhepPlaybackController,
    audioLevelProvider: CallAudioLevelProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(CallState())
    val state = _state.asStateFlow()

    // Effects are process-local commands. Keep a small buffer so the
    // navigation boundary cannot be delayed by a transient collector
    // lifecycle while the repository continues teardown in ApplicationScope.
    private val _effects = MutableSharedFlow<CallEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()
    val localAudioLevel: StateFlow<Float> = audioLevelProvider.localAudioLevel
    val remoteAudioLevel: StateFlow<Float> = audioLevelProvider.remoteAudioLevel

    private var prepareJob: Job? = null
    private var connectJob: Job? = null
    private var screenShareJob: Job? = null
    private var screenShareCreationJob: Job? = null
    private var cleared = false
    private var observedScreenShareCallId: String? = null
    private var pendingPublishRequest: ScreenSharePublishRequest? = null
    private var currentWhepRequest: WhepPlaybackRequest? = null
    private var endRequested: Boolean = false
    private var currentCallId: String? = null
    private var hasStartedCurrentCall: Boolean = false
    private var hasNavigatedHome: Boolean = false
    private val locallyEndedCallIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            observeCallStateUseCase().collect { session ->
                if (session != null) onSessionChanged(session)
            }
        }
        viewModelScope.launch {
            whepPlaybackController.status.collect(::onWhepStatusChanged)
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
            CallIntent.OpenScreenSharePicker -> openScreenSharePicker()
            CallIntent.DismissScreenSharePicker -> dismissScreenSharePicker()
            is CallIntent.SelectPublishQuality -> {
                if (_state.value.screenShare.session?.status !in ACTIVE_SCREEN_SHARE_STATUSES &&
                    !_state.value.screenShare.isCreating && pendingPublishRequest == null
                ) {
                    _state.update { it.copy(screenShare = it.screenShare.copy(publishQuality = intent.quality)) }
                }
            }
            CallIntent.StartMobileScreenShare -> createScreenShare(ScreenShareSource.Mobile)
            CallIntent.StartObsScreenShare -> createScreenShare(ScreenShareSource.Obs)
            is CallIntent.ScreenCapturePermissionResult -> handleScreenCapturePermission(intent)
            CallIntent.StopScreenShare -> stopScreenShare()
            CallIntent.RetryRemoteScreenShare -> retryRemoteScreenShare()
            CallIntent.DismissObsSetup -> dismissObsSetup()
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
        hasStartedCurrentCall = false
        hasNavigatedHome = false
        prepareJob = viewModelScope.launch {
            val existingSession = observeCallStateUseCase().first()
            if (
                request is CallPreparationRequest.Existing &&
                existingSession?.callId == request.callId &&
                existingSession.connectionState.isResumable
            ) {
                currentCallId = existingSession.callId
                hasStartedCurrentCall = true
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
                    hasStartedCurrentCall = true
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

    fun createRemoteScreenRenderer(context: Context): View =
        whepPlaybackController.createVideoRenderer(context)

    fun releaseRemoteScreenRenderer(view: View) {
        whepPlaybackController.releaseVideoRenderer(view)
    }

    private fun openScreenSharePicker() {
        if (_state.value.screenState != CallScreenState.Active) return
        _state.update { current ->
            current.copy(
                screenShare = current.screenShare.copy(sourcePickerVisible = true),
            )
        }
    }

    private fun dismissScreenSharePicker() {
        _state.update { current ->
            current.copy(
                screenShare = current.screenShare.copy(sourcePickerVisible = false),
            )
        }
    }

    private fun dismissObsSetup() {
        _state.update { current ->
            current.copy(
                screenShare = current.screenShare.copy(obsSetupVisible = false),
            )
        }
    }

    private fun createScreenShare(source: ScreenShareSource) {
        val callId = currentCallId ?: return
        if (_state.value.screenState != CallScreenState.Active) return
        if (_state.value.screenShare.session?.status in ACTIVE_SCREEN_SHARE_STATUSES) return
        if (screenShareCreationJob?.isActive == true) return
        val quality = _state.value.screenShare.publishQuality
        _state.update { it.copy(screenShare = it.screenShare.copy(isCreating = true)) }
        screenShareCreationJob = viewModelScope.launch {
            try {
                // A created server share must be compensated even if navigation
                // cancels the screen while the HTTP response is in flight.
                withContext(NonCancellable) {
                    dismissScreenSharePicker()
                    when (val result = createScreenShareUseCase(callId, source)) {
                        is AppResult.Success -> {
                            if (cleared || endRequested || currentCallId != callId ||
                                _state.value.session?.connectionState?.isResumable != true
                            ) {
                                stopScreenShareUseCase(callId)
                                return@withContext
                            }
                            val publishing = result.value.publishing
                            if (publishing == null) {
                                _effects.emit(CallEffect.ShowMessage("服务器未返回投屏发布凭证"))
                                stopScreenShareUseCase(callId)
                                return@withContext
                            }
                            if (source == ScreenShareSource.Obs) {
                                _state.update { current ->
                                    current.copy(
                                        screenShare = current.screenShare.copy(
                                            session = result.value,
                                            isOwnedByCurrentUser = true,
                                            obsPublishing = publishing,
                                            obsSetupVisible = true,
                                        ),
                                    )
                                }
                                return@withContext
                            }
                            val request = ScreenSharePublishRequest(
                                callId = result.value.callId,
                                shareId = result.value.shareId,
                                whipUrl = publishing.whip.url,
                                bearerToken = publishing.whip.bearerToken,
                                expiresAtEpochMillis = publishing.whip.expiresAtEpochMillis,
                                quality = quality,
                            )
                            pendingPublishRequest = request
                            screenSharePublisherController.prepare(request)
                            _effects.emit(CallEffect.RequestScreenCapturePermission(request))
                        }
                        is AppResult.Failure -> {
                            if (cleared || endRequested || currentCallId != callId) return@withContext
                            _effects.emit(
                                CallEffect.ShowMessage(
                                    result.error.toCallUserMessage("无法开始屏幕共享，请稍后重试"),
                                ),
                            )
                        }
                    }
                }
            } finally {
                _state.update { it.copy(screenShare = it.screenShare.copy(isCreating = false)) }
            }
        }
    }

    private fun handleScreenCapturePermission(result: CallIntent.ScreenCapturePermissionResult) {
        val request = pendingPublishRequest ?: return
        pendingPublishRequest = null
        if (cleared || endRequested || currentCallId != request.callId ||
            _state.value.session?.connectionState?.isResumable != true
        ) return
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            screenSharePublisherController.permissionDenied(request)
            viewModelScope.launch {
                stopScreenShareUseCase(request.callId)
                screenSharePublisherController.permissionDenied(request)
                _effects.emit(CallEffect.ShowMessage("未授予屏幕录制权限，语音通话不受影响"))
            }
            return
        }
        screenSharePublisherController.start(
            request,
            ScreenCapturePermission(result.resultCode, result.data),
        )
    }

    private fun stopScreenShare() {
        val callId = currentCallId ?: return
        pendingPublishRequest = null
        screenSharePublisherController.stop(callId)
        stopRemoteScreenPlayback()
        viewModelScope.launch {
            when (val result = stopScreenShareUseCase(callId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> _effects.emit(
                    CallEffect.ShowMessage(
                        result.error.toCallUserMessage("本地投屏已停止，但服务器状态同步失败"),
                    ),
                )
            }
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
        screenShareJob?.cancel()
        screenShareJob = null
        observedScreenShareCallId = null
        pendingPublishRequest = null
        stopRemoteScreenPlayback()
        _state.value = _state.value.copy(
            screenState = CallScreenState.Ended,
            isLoading = false,
            failureMessage = null,
            screenShare = CallScreenShareState(),
        )
        endedCallId?.let { callId ->
            screenSharePublisherController.stop(callId)
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    stopScreenShareUseCase(callId)
                }
            }
        }
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

        val isTerminal = session.connectionState.isTerminal || session.connectionState == CallConnectionState.Terminating
        if (!isTerminal) {
            currentCallId = session.callId
            observeScreenShare(session.callId)
            updateState(session)
            maybeStartRemoteScreenPlayback(
                share = _state.value.screenShare.session,
                isOwnedByCurrentUser = _state.value.screenShare.isOwnedByCurrentUser,
            )
            return
        }

        // A singleton repository intentionally retains the last terminal snapshot.
        // A newly-created screen must not navigate away because of that stale snapshot.
        if (session.callId != currentCallId) return
        screenShareJob?.cancel()
        screenShareJob = null
        observedScreenShareCallId = null
        pendingPublishRequest = null
        screenSharePublisherController.stop(session.callId)
        stopRemoteScreenPlayback()
        updateState(session)
        _state.update { it.copy(screenShare = CallScreenShareState()) }
        if (!endRequested && hasStartedCurrentCall && session.connectionState == CallConnectionState.Disconnected) {
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
            isLoading = screenState in setOf(CallScreenState.Dialing, CallScreenState.Connecting, CallScreenState.Ending),
            failureMessage = if (screenState == CallScreenState.Failed) {
                _state.value.failureMessage ?: CALL_CONNECTION_FAILED_MESSAGE
            } else {
                null
            },
            screenShare = _state.value.screenShare,
        )
    }

    private fun observeScreenShare(callId: String) {
        if (screenShareJob?.isActive == true && observedScreenShareCallId == callId) return
        screenShareJob?.cancel()
        observedScreenShareCallId = callId
        screenShareJob = viewModelScope.launch {
            observeScreenShareUseCase(callId).collect(::onScreenShareChanged)
        }
    }

    private fun onScreenShareChanged(snapshot: ScreenShareSnapshot) {
        if (snapshot.callId != currentCallId || endRequested || cleared ||
            _state.value.session?.connectionState?.isResumable != true
        ) return
        val share = snapshot.session
        val isRemote = share != null && !snapshot.isOwnedByCurrentUser
        val baseRemoteState = when {
            !isRemote -> RemoteScreenShareUiState.None
            share.status == ScreenShareStatus.Authorized -> RemoteScreenShareUiState.Preparing
            share.status == ScreenShareStatus.Live && currentWhepRequest?.shareId == share.shareId ->
                _state.value.screenShare.remoteState
            share.status == ScreenShareStatus.Live -> RemoteScreenShareUiState.Connecting
            share.status == ScreenShareStatus.Stopping || share.status == ScreenShareStatus.Stopped ->
                RemoteScreenShareUiState.Stopped
            share.status == ScreenShareStatus.Failed || share.status == ScreenShareStatus.Expired ->
                RemoteScreenShareUiState.Failed
            else -> RemoteScreenShareUiState.None
        }
        _state.update { current ->
            current.copy(
                screenShare = current.screenShare.copy(
                    session = share,
                    isOwnedByCurrentUser = snapshot.isOwnedByCurrentUser,
                    localState = snapshot.localState,
                    remoteState = baseRemoteState,
                    errorMessage = share?.errorMessage ?: snapshot.syncErrorMessage
                        ?: current.screenShare.errorMessage.takeIf {
                            current.screenShare.session?.shareId == share?.shareId &&
                                baseRemoteState == RemoteScreenShareUiState.Failed
                        },
                    obsSetupVisible = current.screenShare.obsSetupVisible &&
                        share?.status in ACTIVE_SCREEN_SHARE_STATUSES,
                    obsPublishing = if (share?.source == ScreenShareSource.Obs && snapshot.isOwnedByCurrentUser) {
                        share.publishing ?: current.screenShare.obsPublishing.takeIf {
                            current.screenShare.session?.shareId == share.shareId &&
                                share.status in ACTIVE_SCREEN_SHARE_STATUSES
                        }
                    } else {
                        null
                    },
                ),
            )
        }

        if (isRemote && share.status == ScreenShareStatus.Live && share.playback != null) {
            maybeStartRemoteScreenPlayback(share, snapshot.isOwnedByCurrentUser)
        } else if (currentWhepRequest != null) {
            stopRemoteScreenPlayback()
        }
    }

    private fun maybeStartRemoteScreenPlayback(
        share: ScreenShareSession?,
        isOwnedByCurrentUser: Boolean,
    ) {
        val voiceState = _state.value.session?.connectionState
        val voiceMediaReady = voiceState == CallConnectionState.Connected ||
            voiceState == CallConnectionState.Reconnecting
        val endpoint = share?.playback
        if (
            voiceMediaReady &&
            share != null &&
            !isOwnedByCurrentUser &&
            share.status == ScreenShareStatus.Live &&
            endpoint != null
        ) {
            val playbackRequest = WhepPlaybackRequest(
                callId = share.callId,
                shareId = share.shareId,
                url = endpoint.url,
                bearerToken = endpoint.bearerToken,
                expiresAtEpochMillis = endpoint.expiresAtEpochMillis,
            )
            if (currentWhepRequest.shouldReplaceWith(playbackRequest)) {
                currentWhepRequest = playbackRequest
                whepPlaybackController.start(playbackRequest)
            }
        }
    }

    private fun retryRemoteScreenShare() {
        if (endRequested || cleared || _state.value.screenShare.remoteState != RemoteScreenShareUiState.Failed) return
        val request = currentWhepRequest ?: return
        if (request.callId != currentCallId || _state.value.screenShare.session?.shareId != request.shareId) return
        if (request.expiresAtEpochMillis <= System.currentTimeMillis()) {
            viewModelScope.launch { _effects.emit(CallEffect.ShowMessage("播放凭证已过期，请返回首页后继续通话以刷新")) }
            return
        }
        _state.update { it.copy(screenShare = it.screenShare.copy(
            remoteState = RemoteScreenShareUiState.Connecting, errorMessage = null)) }
        whepPlaybackController.start(request)
    }

    private fun stopRemoteScreenPlayback() {
        val shareId = currentWhepRequest?.shareId
        currentWhepRequest = null
        if (shareId != null) whepPlaybackController.stop(shareId)
    }

    override fun onCleared() {
        cleared = true
        pendingPublishRequest = null
        stopRemoteScreenPlayback()
        super.onCleared()
    }

    private fun onWhepStatusChanged(status: WhepPlaybackStatus) {
        if (endRequested || cleared) return
        if (status is WhepPlaybackStatus.Idle) return
        if (status is WhepPlaybackStatus.Stopped &&
            (currentWhepRequest == null || status.shareId != currentWhepRequest?.shareId)
        ) return
        val request = when (status) {
            is WhepPlaybackStatus.Connecting -> status.request
            is WhepPlaybackStatus.Buffering -> status.request
            is WhepPlaybackStatus.Live -> status.request
            is WhepPlaybackStatus.Failed -> status.request
            is WhepPlaybackStatus.Idle,
            is WhepPlaybackStatus.Stopped,
            -> null
        }
        if (request != null && request != currentWhepRequest) {
            return
        }
        val remoteState = when (status) {
            WhepPlaybackStatus.Idle -> RemoteScreenShareUiState.None
            is WhepPlaybackStatus.Connecting -> RemoteScreenShareUiState.Connecting
            is WhepPlaybackStatus.Buffering -> RemoteScreenShareUiState.Buffering
            is WhepPlaybackStatus.Live -> RemoteScreenShareUiState.Live
            is WhepPlaybackStatus.Failed -> RemoteScreenShareUiState.Failed
            is WhepPlaybackStatus.Stopped -> RemoteScreenShareUiState.Stopped
        }
        _state.update { current ->
            current.copy(
                screenShare = current.screenShare.copy(
                    remoteState = remoteState,
                    remoteAspectRatio = if (status is WhepPlaybackStatus.Live &&
                        status.width > 0 && status.height > 0
                    ) {
                        (status.width.toFloat() / status.height).coerceIn(0.2f, 5f)
                    } else {
                        current.screenShare.remoteAspectRatio
                    },
                    errorMessage = (status as? WhepPlaybackStatus.Failed)?.message
                        ?: current.screenShare.errorMessage,
                ),
            )
        }
    }

    private companion object {
        const val CALL_PREPARATION_FAILED_MESSAGE = "通话准备失败，请稍后重试"
        const val CALL_CONNECTION_FAILED_MESSAGE = "通话连接失败，请稍后重试"
        const val CALL_ACTION_FAILED_MESSAGE = "通话操作失败，请稍后重试"
        val ACTIVE_SCREEN_SHARE_STATUSES = setOf(
            ScreenShareStatus.Authorized,
            ScreenShareStatus.Live,
            ScreenShareStatus.Stopping,
        )
    }
}

private fun WhepPlaybackRequest?.shouldReplaceWith(next: WhepPlaybackRequest): Boolean {
    val current = this ?: return true
    if (current.callId != next.callId || current.shareId != next.shareId || current.url != next.url) return true
    val refreshThreshold = System.currentTimeMillis() + WHEP_TOKEN_REFRESH_WINDOW_MILLIS
    return current.expiresAtEpochMillis <= refreshThreshold &&
        next.expiresAtEpochMillis > current.expiresAtEpochMillis
}

private const val WHEP_TOKEN_REFRESH_WINDOW_MILLIS = 30_000L

private fun AppError.toCallUserMessage(fallbackMessage: String): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Unexpected -> fallbackMessage
    else -> toUserMessage().takeIf(String::isNotBlank) ?: fallbackMessage
}

private fun CallSession.toScreenState(): CallScreenState {
    when (connectionState) {
        CallConnectionState.Terminating -> return CallScreenState.Ending
        CallConnectionState.Disconnected -> return CallScreenState.Ended
        is CallConnectionState.Failed -> return CallScreenState.Failed
        else -> Unit
    }
    when (interruptionState.local) {
        is LocalCallInterruption.Resuming -> return CallScreenState.ResumingAfterSystemCall
        is LocalCallInterruption.Suspending,
        is LocalCallInterruption.Suspended,
        -> return CallScreenState.SystemCallSuspended
        LocalCallInterruption.None -> Unit
    }
    when (interruptionState.remote) {
        is RemoteCallInterruption.Resuming -> return CallScreenState.RemoteResumingAfterSystemCall
        is RemoteCallInterruption.Suspended -> return CallScreenState.RemoteSystemCallSuspended
        RemoteCallInterruption.None -> Unit
    }
    return when (connectionState) {
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
}
