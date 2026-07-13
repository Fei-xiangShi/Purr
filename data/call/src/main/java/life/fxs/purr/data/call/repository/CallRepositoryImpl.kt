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
    private var operationId: Long = 0L
    private var prepareOperation: PrepareOperation? = null
    private var connectOperation: ConnectOperation? = null
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
                val terminalState = operationMutex.withLock {
                    val current = sessionState.value
                    // A delayed event from a previous media generation must not mutate a new call.
                    if (current == null || current.callId != mediaEvent.callId) return@withLock null
                    val expectedGeneration = mediaGeneration
                    if (expectedGeneration != null && expectedGeneration != mediaEvent.generation) {
                        return@withLock null
                    }
                    if (
                        current.connectionState == CallConnectionState.Terminating &&
                        mediaEvent !is MediaCallEvent.Disconnected &&
                        mediaEvent !is MediaCallEvent.Failed
                    ) {
                        return@withLock null
                    }
                    if (mediaEvent is MediaCallEvent.Connected) {
                        mediaGeneration = mediaEvent.generation
                    }
                    if (
                        mediaEvent is MediaCallEvent.Disconnected ||
                        mediaEvent is MediaCallEvent.Failed
                    ) {
                        if (current.connectionState.isTerminal() ||
                            current.connectionState == CallConnectionState.Terminating
                        ) {
                            return@withLock null
                        }
                        mediaConnection = null
                        mediaGeneration = null
                        callStatusSynchronizer.stop()
                        sessionState.emit(
                            callUiSnapshotAssembler.assemble(
                                current.copy(
                                    connectionState = CallConnectionState.Terminating,
                                    localAudioState = LocalAudioState.Disabled,
                                    uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                                ),
                            ),
                        )
                        return@withLock when (mediaEvent) {
                            is MediaCallEvent.Failed -> CallConnectionState.Failed(mediaEvent.reason)
                            else -> CallConnectionState.Disconnected
                        }
                    }

                    val synchronizedSession = callMediaEventReducer.reduce(current, mediaEvent)
                        ?: return@withLock null
                    sessionState.emit(callUiSnapshotAssembler.assemble(synchronizedSession))
                    null
                }
                if (terminalState != null) {
                    terminateCall(
                        expectedCallId = mediaEvent.callId,
                        terminalState = terminalState,
                    )
                }
            }
        }
    }

    override fun observeCallSession(): Flow<CallSession?> = sessionState.asStateFlow()

    override suspend fun prepareCall(params: PrepareCallParams): AppResult<CallSession> {
        var immediateResult: AppResult<CallSession>? = null
        val operation = operationMutex.withLock {
            val inFlight = prepareOperation
            if (inFlight != null) {
                if (inFlight.params == params) return@withLock inFlight.deferred
                immediateResult = AppResult.Failure(AppError.Validation("A different call is being prepared"))
                return@withLock null
            }
            if (disconnectOperation != null) {
                immediateResult = AppResult.Failure(
                    AppError.Validation("The previous call is still being cleaned up"),
                )
                return@withLock null
            }
            val existing = sessionState.value
            if (existing != null && !existing.connectionState.isTerminal()) {
                immediateResult = AppResult.Failure(AppError.Validation("A call is already in progress"))
                return@withLock null
            }

            val id = ++operationId
            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                performPrepareCall(id, params)
            }
            prepareOperation = PrepareOperation(id, params, deferred)
            deferred
        }

        immediateResult?.let { return it }
        val activeOperation = requireNotNull(operation)
        activeOperation.start()
        return activeOperation.await()
    }

    private suspend fun performPrepareCall(
        id: Long,
        params: PrepareCallParams,
    ): AppResult<CallSession> {
        var createdCallId: String? = null
        return try {
            val response = api.createSession(
                SessionRequestDto(
                    pairId = params.pairId,
                    recordingConsent = params.recordingConsent,
                ),
            )
            createdCallId = response.callId
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
            operationMutex.withLock {
                mediaConnection = CallMediaConnection(
                    wsUrl = response.wsUrl,
                    accessToken = response.token,
                )
                mediaGeneration = null
                sessionState.emit(session)
            }
            startCallStatusSync(session.callId)
            AppResult.Success(session)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            createdCallId?.let { bestEffortEndCall(it) }
            AppResult.Failure(throwable.asAppError())
        } finally {
            operationMutex.withLock {
                if (prepareOperation?.id == id) prepareOperation = null
            }
        }
    }

    override suspend fun connectCall(): AppResult<Unit> {
        var immediateResult: AppResult<Unit>? = null
        val operation = operationMutex.withLock {
            val session = sessionState.value
            val inFlight = connectOperation
            if (inFlight != null) {
                if (session?.callId == inFlight.callId) return@withLock inFlight.deferred
                immediateResult = AppResult.Failure(AppError.Validation("A different call is connecting"))
                return@withLock null
            }
            if (disconnectOperation != null) {
                immediateResult = AppResult.Failure(AppError.Validation("Call termination is in progress"))
                return@withLock null
            }
            if (session == null) {
                immediateResult = AppResult.Failure(AppError.Validation("No prepared call session"))
                return@withLock null
            }
            if (session.connectionState != CallConnectionState.Preparing) {
                immediateResult = AppResult.Failure(AppError.Validation("Call session is no longer connectable"))
                return@withLock null
            }
            val connection = mediaConnection
            if (connection == null) {
                immediateResult = AppResult.Failure(AppError.Validation("Call media credentials are unavailable"))
                return@withLock null
            }

            val id = ++operationId
            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                performConnectCall(id, session, connection)
            }
            connectOperation = ConnectOperation(id, session.callId, deferred)
            deferred
        }

        immediateResult?.let { return it }
        val activeOperation = requireNotNull(operation)
        activeOperation.start()
        return activeOperation.await()
    }

    private suspend fun performConnectCall(
        id: Long,
        session: CallSession,
        connection: CallMediaConnection,
    ): AppResult<Unit> {
        return try {
            val canConnect = operationMutex.withLock {
                val current = sessionState.value
                if (current?.callId != session.callId ||
                    current.connectionState != CallConnectionState.Preparing
                ) {
                    false
                } else {
                    sessionState.emit(
                        callUiSnapshotAssembler.assemble(
                            current.copy(
                                connectionState = CallConnectionState.Connecting,
                                localAudioState = LocalAudioState.Enabling,
                            ),
                        ),
                    )
                    true
                }
            }
            if (!canConnect) {
                AppResult.Failure(AppError.Validation("Call session is no longer connectable"))
            } else {
                callRuntimeController.execute(
                    MediaCallCommand.Connect(
                        callId = session.callId,
                        pairId = session.pairId,
                        localIdentity = session.participantIdentity.local,
                        connection = connection,
                    ),
                )
                AppResult.Success(Unit)
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            terminateCall(
                expectedCallId = session.callId,
                terminalState = CallConnectionState.Failed(throwable.message),
            )
            AppResult.Failure(throwable.asAppError())
        } finally {
            operationMutex.withLock {
                if (connectOperation?.id == id) connectOperation = null
            }
        }
    }

    override suspend fun disconnectCall(expectedCallId: String?): AppResult<Unit> = terminateCall(
        expectedCallId = expectedCallId,
        terminalState = CallConnectionState.Disconnected,
    )

    private suspend fun terminateCall(
        expectedCallId: String?,
        terminalState: CallConnectionState,
    ): AppResult<Unit> {
        val pendingPrepare = operationMutex.withLock {
            prepareOperation?.deferred.takeIf { sessionState.value == null }
        }
        if (pendingPrepare != null) {
            pendingPrepare.start()
            pendingPrepare.await()
            return terminateCall(expectedCallId, terminalState)
        }

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

            mediaConnection = null
            mediaGeneration = null
            callStatusSynchronizer.stop()
            if (session.connectionState != CallConnectionState.Terminating) {
                sessionState.emit(
                    callUiSnapshotAssembler.assemble(
                        session.copy(
                            connectionState = CallConnectionState.Terminating,
                            localAudioState = LocalAudioState.Disabled,
                            uiSnapshot = session.uiSnapshot.copy(remoteParticipantConnected = false),
                        ),
                    ),
                )
            }

            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                disconnectSession(session, terminalState)
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
    private suspend fun disconnectSession(
        session: CallSession,
        requestedTerminalState: CallConnectionState,
    ): AppResult<Unit> {
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

        try {
            // Server synchronization intentionally happens outside operationMutex. A slow or
            // temporarily unavailable API must not block unrelated UI state reads.
            api.endCall(session.callId)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (failure == null) failure = throwable else failure?.addSuppressed(throwable)
        } finally {
            operationMutex.withLock {
                if (disconnectOperation?.callId == session.callId) {
                    disconnectOperation = null
                }
                val current = sessionState.value
                if (current?.callId == session.callId) {
                    val finalState = failure
                        ?.let { CallConnectionState.Failed(it.message) }
                        ?: requestedTerminalState
                    sessionState.emit(
                        callUiSnapshotAssembler.assemble(
                            current.copy(
                                connectionState = finalState,
                                localAudioState = LocalAudioState.Disabled,
                                uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                            ),
                        ),
                    )
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

private data class PrepareOperation(
    val id: Long,
    val params: PrepareCallParams,
    val deferred: Deferred<AppResult<CallSession>>,
)

private data class ConnectOperation(
    val id: Long,
    val callId: String,
    val deferred: Deferred<AppResult<Unit>>,
)

private fun CallConnectionState.isTerminal(): Boolean =
    this == CallConnectionState.Disconnected || this is CallConnectionState.Failed
