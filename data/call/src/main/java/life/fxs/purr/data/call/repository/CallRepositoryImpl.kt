package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.asAppError
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioFocusManager
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.SessionRequestDto
import life.fxs.purr.data.call.livekit.LiveKitCallDataSource
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.repository.CallRepository

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val api: PurrCallApi,
    private val liveKitCallDataSource: LiveKitCallDataSource,
    private val audioRouteController: AudioRouteController,
    private val callAudioFocusManager: CallAudioFocusManager,
    private val callServiceController: CallServiceController,
) : CallRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionState = MutableStateFlow<CallSession?>(null)
    private var callStatusSyncJob: Job? = null

    init {
        repositoryScope.launch {
            liveKitCallDataSource.sessionEvents.collect { session ->
                if (session == null) return@collect

                if (
                    session.connectionState == CallConnectionState.Disconnected ||
                    session.connectionState is CallConnectionState.Failed
                ) {
                    stopCallStatusSync()
                    callServiceController.stopForegroundCall()
                    callAudioFocusManager.abandonFocus()
                }

                sessionState.emit(syncUiSnapshot(session))
            }
        }
    }

    override fun observeCallSession(): Flow<CallSession?> = sessionState.asStateFlow()

    override suspend fun prepareCall(params: PrepareCallParams): AppResult<CallSession> = appResult {
        val response = api.createSession(
            SessionRequestDto(
                pairId = params.pairId,
                resumeCallId = params.resumeCallId,
                recordingConsent = params.recordingConsent,
            ),
        )
        val recordingState = fetchRecordingState(response.callId)
        val session = syncUiSnapshot(
            CallSession(
                callId = response.callId,
                pairId = response.pairId,
                participantIdentity = ParticipantIdentity(local = response.participantIdentity),
                roomName = response.roomName,
                wsUrl = response.wsUrl,
                token = response.token,
                connectionState = CallConnectionState.Preparing,
                localAudioState = LocalAudioState.Disabled,
                recordingState = recordingState,
                uiSnapshot = CallUiSnapshot(),
            ),
        )
        sessionState.emit(session)
        liveKitCallDataSource.updateSession(session)
        startCallStatusSync(session.callId)
        session
    }

    override suspend fun connectCall(): AppResult<Unit> {
        val session = sessionState.value ?: return AppResult.Failure(AppError.Validation("No prepared call session"))
        return appResult(
            onFailure = { throwable ->
                callAudioFocusManager.abandonFocus()
                callServiceController.stopForegroundCall()
                val failedSession = syncUiSnapshot(
                    session.copy(
                        connectionState = CallConnectionState.Failed(throwable.message),
                        localAudioState = LocalAudioState.Error(throwable.message),
                    ),
                )
                sessionState.emit(failedSession)
                liveKitCallDataSource.updateSession(failedSession)
            },
        ) {
            val focusGranted = callAudioFocusManager.requestFocus()
            check(focusGranted) { "Unable to gain call audio focus" }
            callServiceController.startForegroundCall(session.callId)

            val connectingSession = syncUiSnapshot(
                session.copy(
                    connectionState = CallConnectionState.Connecting,
                    localAudioState = LocalAudioState.Enabling,
                ),
            )
            sessionState.emit(connectingSession)
            liveKitCallDataSource.updateSession(connectingSession)
            liveKitCallDataSource.connect(connectingSession)
        }
    }

    override suspend fun disconnectCall(): AppResult<Unit> {
        val session = sessionState.value ?: return AppResult.Success(Unit)
        return appResult(
            onFailure = {
                val failedSession = syncUiSnapshot(
                    session.copy(
                        connectionState = CallConnectionState.Failed(it.message),
                    ),
                )
                sessionState.emit(failedSession)
                liveKitCallDataSource.updateSession(failedSession)
            },
        ) {
            api.endCall(session.callId)
            stopCallStatusSync()
            liveKitCallDataSource.disconnect()
            callServiceController.stopForegroundCall()
            callAudioFocusManager.abandonFocus()
            val disconnectedSession = syncUiSnapshot(
                session.copy(
                    connectionState = CallConnectionState.Disconnected,
                    localAudioState = LocalAudioState.Disabled,
                    uiSnapshot = session.uiSnapshot.copy(remoteParticipantConnected = false),
                ),
            )
            sessionState.emit(disconnectedSession)
            liveKitCallDataSource.updateSession(disconnectedSession)
        }
    }

    override suspend fun setMuted(muted: Boolean): AppResult<Unit> {
        val session = sessionState.value ?: return AppResult.Failure(AppError.Validation("No call session"))
        return appResult(
            onFailure = {
                val failedSession = syncUiSnapshot(session.copy(localAudioState = LocalAudioState.Error(it.message)))
                sessionState.emit(failedSession)
                liveKitCallDataSource.updateSession(failedSession)
            },
        ) {
            liveKitCallDataSource.setMuted(muted)
            val updatedSession = syncUiSnapshot(
                requireNotNull(sessionState.value ?: session).copy(
                    localAudioState = if (muted) LocalAudioState.Muted else LocalAudioState.Enabled,
                ),
            )
            sessionState.emit(updatedSession)
            liveKitCallDataSource.updateSession(updatedSession)
        }
    }

    override suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit> {
        val session = sessionState.value ?: return AppResult.Failure(AppError.Validation("No call session"))
        return appResult(
            onFailure = {
                val failedSession = syncUiSnapshot(session)
                sessionState.emit(failedSession)
                liveKitCallDataSource.updateSession(failedSession)
            },
        ) {
            audioRouteController.selectRoute(route)
            val updatedSession = syncUiSnapshot(requireNotNull(sessionState.value ?: session))
            sessionState.emit(updatedSession)
            liveKitCallDataSource.updateSession(updatedSession)
        }
    }

    private fun syncUiSnapshot(session: CallSession): CallSession {
        return session.copy(
            uiSnapshot = session.uiSnapshot.copy(
                activeAudioRoute = audioRouteController.activeRoute.value,
                availableAudioRoutes = audioRouteController.availableRoutes.value,
                isForegroundServiceActive = callServiceController.isCallForeground.value,
            ),
        )
    }

    private fun startCallStatusSync(callId: String) {
        stopCallStatusSync()
        callStatusSyncJob = repositoryScope.launch {
            while (isActive) {
                val currentSession = sessionState.value ?: break
                if (currentSession.callId != callId) {
                    break
                }
                try {
                    val callStatus = api.getCall(callId)
                    val latestSession = sessionState.value ?: break
                    if (latestSession.callId != callId) {
                        break
                    }
                    val syncedSession = syncUiSnapshot(
                        latestSession.copy(
                            recordingState = callStatus.recordingStatus.toRecordingState(),
                        ),
                    )
                    if (callStatus.state.lowercase() == "ended") {
                        liveKitCallDataSource.disconnect()
                        callServiceController.stopForegroundCall()
                        callAudioFocusManager.abandonFocus()
                        val endedSession = syncUiSnapshot(
                            syncedSession.copy(
                                connectionState = CallConnectionState.Disconnected,
                                localAudioState = LocalAudioState.Disabled,
                                uiSnapshot = syncedSession.uiSnapshot.copy(remoteParticipantConnected = false),
                            ),
                        )
                        sessionState.emit(endedSession)
                        liveKitCallDataSource.updateSession(endedSession)
                        break
                    }
                    if (syncedSession != latestSession) {
                        sessionState.emit(syncedSession)
                        liveKitCallDataSource.updateSession(syncedSession)
                    }
                } catch (_: Throwable) {
                    // Keep last known state and retry on the next tick.
                }
                delay(CALL_STATUS_SYNC_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopCallStatusSync() {
        callStatusSyncJob?.cancel()
        callStatusSyncJob = null
    }

    private suspend fun fetchRecordingState(
        callId: String,
        fallbackStatus: String? = null,
    ): RecordingState {
        return try {
            api.getCall(callId).recordingStatus.toRecordingState()
        } catch (throwable: Throwable) {
            fallbackStatus?.toRecordingState() ?: throw throwable
        }
    }

    private suspend inline fun <T> appResult(
        noinline onFailure: suspend (Throwable) -> Unit = {},
        block: suspend () -> T,
    ): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            onFailure(throwable)
            AppResult.Failure(throwable.asAppError())
        }
    }

    private companion object {
        const val CALL_STATUS_SYNC_INTERVAL_MILLIS = 2_000L
    }
}

private fun String.toRecordingState(): RecordingState = when (lowercase()) {
    "idle",
    "stopped",
    -> RecordingState.NotRecording
    "starting" -> RecordingState.Starting
    "recording" -> RecordingState.Recording
    "stopping" -> RecordingState.Stopping
    "failed" -> RecordingState.Failed()
    else -> RecordingState.Failed("Unknown recording status: $this")
}
