package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.SessionRequestDto
import life.fxs.purr.data.call.mapper.toCallTiming
import life.fxs.purr.data.call.mapper.toRecordingState
import life.fxs.purr.data.call.remote.CallStatusRemoteDataSource
import life.fxs.purr.data.call.remote.CallStatusSynchronizer
import life.fxs.purr.data.call.runtime.CallMediaConnection
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.CallRuntimeController
import life.fxs.purr.data.call.state.CallMediaEventReducer
import life.fxs.purr.data.call.state.CallUiSnapshotAssembler
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.repository.CallRepository

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val api: PurrCallApi,
    private val callRuntimeController: CallRuntimeController,
    private val callStatusRemoteDataSource: CallStatusRemoteDataSource,
    private val callStatusSynchronizer: CallStatusSynchronizer,
    private val callMediaEventReducer: CallMediaEventReducer,
    private val callUiSnapshotAssembler: CallUiSnapshotAssembler,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : CallRepository {
    private val repositoryScope = applicationScope
    private val sessionState = MutableStateFlow<CallSession?>(null)
    private val operationMutex = Mutex()
    private var mediaConnection: CallMediaConnection? = null
    private var mediaGeneration: Long? = null
    private var disconnectOperation: DisconnectOperation? = null

    init {
        repositoryScope.launch {
            callUiSnapshotAssembler.runtimeState.collect { runtimeState ->
                operationMutex.withLock {
                    val current = sessionState.value ?: return@withLock
                    val updated = callUiSnapshotAssembler.assemble(current, runtimeState)
                    if (updated == current) return@withLock
                    sessionState.emit(updated)
                }
            }
        }
        repositoryScope.launch {
            callRuntimeController.mediaEvents.collect { mediaEvent ->
                operationMutex.withLock {
                    val current = sessionState.value
                    // A delayed event from a previous media generation must not mutate a new call.
                    if (current == null || current.callId != mediaEvent.callId) return@withLock
                    val expectedGeneration = mediaGeneration
                    if (expectedGeneration != null && expectedGeneration != mediaEvent.generation) {
                        return@withLock
                    }
                    if (mediaEvent is MediaCallEvent.Connected) {
                        mediaGeneration = mediaEvent.generation
                    }
                    var synchronizedSession = callMediaEventReducer.reduce(current, mediaEvent)
                        ?: return@withLock
                    if (
                        mediaEvent is MediaCallEvent.Disconnected ||
                        mediaEvent is MediaCallEvent.Failed
                    ) {
                        mediaConnection = null
                        mediaGeneration = null
                        callStatusSynchronizer.stop()
                        runCatching { callRuntimeController.releaseResources() }
                            .exceptionOrNull()
                            ?.let { cleanupFailure ->
                                synchronizedSession = synchronizedSession.copy(
                                    connectionState = CallConnectionState.Failed(cleanupFailure.message),
                                )
                            }
                    }

                    sessionState.emit(callUiSnapshotAssembler.assemble(synchronizedSession))
                }
            }
        }
    }

    override fun observeCallSession(): Flow<CallSession?> = sessionState.asStateFlow()

    override suspend fun prepareCall(params: PrepareCallParams): AppResult<CallSession> = operationMutex.withLock {
        if (disconnectOperation != null) {
            return@withLock AppResult.Failure(
                AppError.Validation("The previous call is still being cleaned up"),
            )
        }
        val existing = sessionState.value
        if (existing != null && !existing.connectionState.isTerminal()) {
            return@withLock AppResult.Failure(
                AppError.Validation("A call is already in progress"),
            )
        }
        appResult {
            val response = api.createSession(
                SessionRequestDto(
                    pairId = params.pairId,
                    recordingConsent = params.recordingConsent,
                ),
            )
            val callStatus = callStatusRemoteDataSource.getStatus(response.callId)
            val session = callUiSnapshotAssembler.assemble(
                CallSession(
                    callId = response.callId,
                    pairId = response.pairId,
                    participantIdentity = ParticipantIdentity(local = response.participantIdentity),
                    roomName = response.roomName,
                    connectionState = CallConnectionState.Preparing,
                    localAudioState = LocalAudioState.Disabled,
                    recordingState = callStatus.recordingStatus.toRecordingState(),
                    timing = callStatus.toCallTiming(),
                    uiSnapshot = CallUiSnapshot(),
                ),
            )
            mediaConnection = CallMediaConnection(
                wsUrl = response.wsUrl,
                accessToken = response.token,
            )
            mediaGeneration = null
            sessionState.emit(session)
            startCallStatusSync(session.callId)
            session
        }
    }

    override suspend fun connectCall(): AppResult<Unit> = operationMutex.withLock {
        val session = sessionState.value
            ?: return@withLock AppResult.Failure(AppError.Validation("No prepared call session"))
        if (session.connectionState != CallConnectionState.Preparing) {
            return@withLock AppResult.Failure(AppError.Validation("Call session is no longer connectable"))
        }
        val connection = mediaConnection
            ?: return@withLock AppResult.Failure(AppError.Validation("Call media credentials are unavailable"))
        appResult(
            onFailure = { throwable ->
                mediaConnection = null
                callStatusSynchronizer.stop()
                bestEffortEndCall(session.callId)
                runCatching { callRuntimeController.releaseResources() }
                    .onFailure(throwable::addSuppressed)
                val failedSession = callUiSnapshotAssembler.assemble(
                    session.copy(
                        connectionState = CallConnectionState.Failed(throwable.message),
                        localAudioState = LocalAudioState.Error(throwable.message),
                    ),
                )
                sessionState.emit(failedSession)
            },
        ) {
            val connectingSession = callUiSnapshotAssembler.assemble(
                session.copy(
                    connectionState = CallConnectionState.Connecting,
                    localAudioState = LocalAudioState.Enabling,
                ),
            )
            sessionState.emit(connectingSession)
            callRuntimeController.execute(
                MediaCallCommand.Connect(
                    callId = connectingSession.callId,
                    pairId = connectingSession.pairId,
                    localIdentity = connectingSession.participantIdentity.local,
                    connection = connection,
                ),
            )
        }
    }

    override suspend fun disconnectCall(expectedCallId: String?): AppResult<Unit> {
        val operation = operationMutex.withLock {
            val currentOperation = disconnectOperation
            if (currentOperation != null) {
                return@withLock if (expectedCallId == null || expectedCallId == currentOperation.callId) {
                    currentOperation.deferred
                } else {
                    null
                }
            }

            val session = sessionState.value
                ?: return@withLock null
            if (expectedCallId != null && session.callId != expectedCallId) {
                return@withLock null
            }
            if (session.connectionState.isTerminal()) {
                return@withLock null
            }

            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                disconnectSession(session)
            }
            disconnectOperation = DisconnectOperation(session.callId, deferred)
            deferred
        }

        if (operation == null) return AppResult.Success(Unit)
        operation.start()
        return operation.await()
    }

    /**
     * Performs local teardown before the server request. The caller is an application-scoped
     * operation so a screen being popped cannot cancel microphone/foreground-service cleanup.
     */
    private suspend fun disconnectSession(session: CallSession): AppResult<Unit> {
        var failure: Throwable? = null

        try {
            callRuntimeController.execute(MediaCallCommand.Disconnect(session.callId))
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            failure = throwable
            runCatching { callRuntimeController.releaseResources() }
                .exceptionOrNull()
                ?.let { cleanupFailure -> failure?.addSuppressed(cleanupFailure) }
        }

        callStatusSynchronizer.stop()
        operationMutex.withLock {
            val current = sessionState.value
            if (current?.callId == session.callId) {
                mediaConnection = null
                mediaGeneration = null
                sessionState.emit(
                    callUiSnapshotAssembler.assemble(
                        current.copy(
                            connectionState = CallConnectionState.Disconnected,
                            localAudioState = LocalAudioState.Disabled,
                            uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                        ),
                    ),
                )
            }
        }

        try {
            // Server synchronization intentionally happens outside operationMutex. A slow or
            // temporarily unavailable API must not block local state transitions or new UI reads.
            api.endCall(session.callId)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (failure == null) failure = throwable else failure?.addSuppressed(throwable)
        } finally {
            operationMutex.withLock {
                if (disconnectOperation?.callId == session.callId) {
                    disconnectOperation = null
                }
            }
        }

        return failure?.let { AppResult.Failure(it.asAppError()) } ?: AppResult.Success(Unit)
    }

    override suspend fun setMuted(muted: Boolean): AppResult<Unit> = operationMutex.withLock {
        val session = sessionState.value
            ?: return@withLock AppResult.Failure(AppError.Validation("No call session"))
        if (session.connectionState != CallConnectionState.Connected) {
            return@withLock AppResult.Failure(AppError.Validation("Call media is not connected"))
        }
        appResult(
            onFailure = {
                val failedSession = callUiSnapshotAssembler.assemble(
                    session.copy(localAudioState = LocalAudioState.Error(it.message)),
                )
                sessionState.emit(failedSession)
            },
        ) {
            callRuntimeController.execute(
                MediaCallCommand.SetMuted(
                    callId = session.callId,
                    muted = muted,
                ),
            )
            val updatedSession = callUiSnapshotAssembler.assemble(
                requireNotNull(sessionState.value ?: session).copy(
                    localAudioState = if (muted) LocalAudioState.Muted else LocalAudioState.Enabled,
                ),
            )
            sessionState.emit(updatedSession)
        }
    }

    override suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit> = operationMutex.withLock {
        val session = sessionState.value
            ?: return@withLock AppResult.Failure(AppError.Validation("No call session"))
        if (session.connectionState != CallConnectionState.Connected) {
            return@withLock AppResult.Failure(AppError.Validation("Call media is not connected"))
        }
        appResult(
            onFailure = {
                val failedSession = callUiSnapshotAssembler.assemble(session)
                sessionState.emit(failedSession)
            },
        ) {
            callRuntimeController.selectAudioRoute(route)
            val updatedSession = callUiSnapshotAssembler.assemble(requireNotNull(sessionState.value ?: session))
            sessionState.emit(updatedSession)
        }
    }

    private fun startCallStatusSync(callId: String) {
        callStatusSynchronizer.start(callId) { callStatus ->
            operationMutex.withLock {
                val latestSession = sessionState.value
                if (latestSession == null || latestSession.callId != callId) return@withLock false
                val syncedSession = callUiSnapshotAssembler.assemble(
                    latestSession.copy(
                        recordingState = callStatus.recordingStatus.toRecordingState(),
                        timing = callStatus.toCallTiming(previous = latestSession.timing),
                    ),
                )
                if (callStatus.state.equals("ended", ignoreCase = true)) {
                    mediaConnection = null
                    mediaGeneration = null
                    runCatching {
                        callRuntimeController.execute(MediaCallCommand.Disconnect(callId))
                    }
                    val endedSession = callUiSnapshotAssembler.assemble(
                        syncedSession.copy(
                            connectionState = CallConnectionState.Disconnected,
                            localAudioState = LocalAudioState.Disabled,
                            uiSnapshot = syncedSession.uiSnapshot.copy(remoteParticipantConnected = false),
                        ),
                    )
                    sessionState.emit(endedSession)
                    return@withLock false
                }
                if (syncedSession != latestSession) {
                    sessionState.emit(syncedSession)
                }
                true
            }
        }
    }

    private suspend fun bestEffortEndCall(callId: String) {
        try {
            api.endCall(callId)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
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

}

private data class DisconnectOperation(
    val callId: String,
    val deferred: Deferred<AppResult<Unit>>,
)

private fun CallConnectionState.isTerminal(): Boolean =
    this == CallConnectionState.Disconnected || this is CallConnectionState.Failed
