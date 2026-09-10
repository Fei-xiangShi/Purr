package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetry
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetryOperation
import life.fxs.purr.core.media.telemetry.CallInterruptionTransitionContext
import life.fxs.purr.core.media.telemetry.CallInterruptionTransitionPhase
import life.fxs.purr.core.media.telemetry.NoOpCallInterruptionTelemetry
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.data.call.mapper.toCallTiming
import life.fxs.purr.data.call.mapper.toRecordingState
import life.fxs.purr.data.call.remote.CallStatusSynchronizer
import life.fxs.purr.data.call.runtime.CallMediaConnection
import life.fxs.purr.data.call.runtime.CallTerminationException
import life.fxs.purr.data.call.runtime.CallTerminationSignal
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.MediaSystemCallInterruptionResult
import life.fxs.purr.data.call.runtime.MediaSystemCallResumeRequest
import life.fxs.purr.data.call.runtime.MediaSystemCallSuspendRequest
import life.fxs.purr.data.call.runtime.CallRuntimeController
import life.fxs.purr.data.call.state.CallMediaEventReducer
import life.fxs.purr.data.call.state.CallUiSnapshotAssembler
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallLifecycleState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalCallInterruption
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.repository.CallRepository

@Singleton
class CallRepositoryImpl @Inject internal constructor(
    private val api: PurrCallApi,
    private val sessionPreparationCoordinator: CallSessionPreparationCoordinator,
    private val callRuntimeController: CallRuntimeController,
    private val callStatusSynchronizer: CallStatusSynchronizer,
    private val callMediaEventReducer: CallMediaEventReducer,
    private val callUiSnapshotAssembler: CallUiSnapshotAssembler,
    private val logger: PurrLogger,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val interruptionTelemetry: CallInterruptionTelemetry = NoOpCallInterruptionTelemetry,
) : CallRepository {
    private val repositoryScope = applicationScope
    private val sessionState = MutableStateFlow<CallSession?>(null)
    private val disconnectingCallIdState = MutableStateFlow<String?>(null)
    private val operationMutex = Mutex()
    private var mediaConnection: CallMediaConnection? = null
    private var mediaGeneration: Long? = null
    private var operationId: Long = 0L
    private var lifecycleGeneration: Long = 0L
    private var currentLifecycleGeneration: Long? = null
    private var prepareOperation: PrepareOperation? = null
    private var connectOperation: ConnectOperation? = null
    private var systemInterruptionOwner: SystemInterruptionOwner? = null
    /**
     * Teardown is call-scoped. A previous call may still be releasing native
     * resources while the next call is already being prepared; a single
     * operation slot would make the next call's hang-up request disappear.
     */
    private val disconnectOperations = mutableMapOf<String, DisconnectOperation>()
    private val serverEndJobs = mutableMapOf<String, Job>()
    private val serverEndedCallIds = mutableSetOf<String>()

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
                    if (
                        mediaEvent is MediaCallEvent.Connected ||
                        mediaEvent is MediaCallEvent.Reconnecting ||
                        mediaEvent is MediaCallEvent.Reconnected
                    ) {
                        mediaGeneration = mediaEvent.generation
                    }
                    if (
                        mediaEvent is MediaCallEvent.Disconnected ||
                        mediaEvent is MediaCallEvent.Failed
                    ) {
                        if (current.connectionState.isTerminal ||
                            current.connectionState == CallConnectionState.Terminating
                        ) {
                            return@withLock null
                        }
                        mediaConnection = null
                        mediaGeneration = null
                        currentLifecycleGeneration = null
                        cancelSystemInterruptionLocked(clearOwner = true)
                        callStatusSynchronizer.stop()
                        sessionState.emit(
                            current.copy(
                                connectionState = CallConnectionState.Terminating,
                                localAudioState = LocalAudioState.Disabled,
                                uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                            ),
                        )
                        return@withLock when (mediaEvent) {
                            is MediaCallEvent.Failed -> CallConnectionState.Failed(mediaEvent.reason)
                            else -> CallConnectionState.Disconnected
                        }
                    }

                    val synchronizedSession = callMediaEventReducer.reduce(current, mediaEvent)
                        ?: return@withLock null
                    sessionState.emit(synchronizedSession)
                    null
                }
                if (terminalState != null) {
                    terminateCall(
                        callId = mediaEvent.callId,
                        terminalState = terminalState,
                    )
                }
            }
        }
    }

    override fun observeCallSession(): Flow<CallSession?> = sessionState.asStateFlow()

    override fun observeCallLifecycle(): Flow<CallLifecycleState> = combine(
        sessionState,
        disconnectingCallIdState,
    ) { session, disconnectingCallId ->
        CallLifecycleState(
            session = session,
            disconnectingCallId = disconnectingCallId,
        )
    }.distinctUntilChanged()

    override suspend fun prepareCall(request: CallPreparationRequest): AppResult<CallSession> {
        while (true) {
            var immediateResult: AppResult<CallSession>? = null
            val operation = operationMutex.withLock {
                val inFlight = prepareOperation
                if (inFlight != null) {
                    if (inFlight.request == request) return@withLock inFlight.deferred
                    immediateResult = AppResult.Failure(AppError.Validation("A different call is being prepared"))
                    return@withLock null
                }
                val existing = sessionState.value
                if (existing != null &&
                    existing.connectionState.isOngoing &&
                    existing.connectionState != CallConnectionState.Terminating
                ) {
                    immediateResult = AppResult.Failure(AppError.Validation("A call is already in progress"))
                    return@withLock null
                }

                val id = ++operationId
                val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                    performPrepareCall(id, request)
                }
                prepareOperation = PrepareOperation(id, request, deferred)
                deferred
            }

            immediateResult?.let { return it }
            val activeOperation = requireNotNull(operation)
            activeOperation.start()
            return activeOperation.await()
        }
    }

    override suspend fun cancelCallPreparation() {
        operationMutex.withLock {
            prepareOperation?.deferred?.cancel(CancellationException("Call preparation cancelled"))
        }
    }

    private suspend fun performPrepareCall(
        id: Long,
        request: CallPreparationRequest,
    ): AppResult<CallSession> {
        var prepared: PreparedCallSession? = null
        return try {
            val created = sessionPreparationCoordinator.prepare(request)
            prepared = created
            operationMutex.withLock {
                cancelSystemInterruptionLocked(clearOwner = true)
                mediaConnection = created.mediaConnection
                mediaGeneration = null
                currentLifecycleGeneration = null
                sessionState.emit(created.session)
            }
            startCallStatusSync(created.session.callId)
            AppResult.Success(created.session)
        } catch (throwable: Throwable) {
            prepared?.let { committed ->
                sessionPreparationCoordinator.compensate(committed)
                operationMutex.withLock {
                    if (sessionState.value?.callId == committed.session.callId) {
                        mediaConnection = null
                        mediaGeneration = null
                        currentLifecycleGeneration = null
                        callStatusSynchronizer.stop()
                        sessionState.emit(null)
                    }
                }
            }
            if (throwable is CancellationException) throw throwable
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
            if (session == null) {
                immediateResult = AppResult.Failure(AppError.Validation("No prepared call session"))
                return@withLock null
            }
            if (disconnectOperations.containsKey(session.callId)) {
                immediateResult = AppResult.Failure(AppError.Validation("Call termination is in progress"))
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
            val generation = ++lifecycleGeneration
            cancelSystemInterruptionLocked(clearOwner = true)
            currentLifecycleGeneration = generation
            val terminationSignal = CallTerminationSignal()
            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                performConnectCall(id, generation, session, connection, terminationSignal)
            }
            connectOperation = ConnectOperation(
                id = id,
                generation = generation,
                callId = session.callId,
                terminationSignal = terminationSignal,
                deferred = deferred,
            )
            deferred
        }

        immediateResult?.let { return it }
        val activeOperation = requireNotNull(operation)
        activeOperation.start()
        return activeOperation.await()
    }

    private suspend fun performConnectCall(
        id: Long,
        generation: Long,
        session: CallSession,
        connection: CallMediaConnection,
        terminationSignal: CallTerminationSignal,
    ): AppResult<Unit> {
        return try {
            val canConnect = operationMutex.withLock {
                val current = sessionState.value
                val operation = connectOperation
                if (operation?.id != id || operation.generation != generation ||
                    terminationSignal.isRequested || current?.callId != session.callId ||
                    current.connectionState != CallConnectionState.Preparing
                ) {
                    false
                } else {
                    sessionState.emit(
                        current.copy(
                            connectionState = CallConnectionState.Connecting,
                            localAudioState = LocalAudioState.Enabling,
                        ),
                    )
                    true
                }
            }
            if (!canConnect) {
                AppResult.Failure(AppError.Validation("Call session is no longer connectable"))
            } else {
                withTimeout(CONNECT_TIMEOUT_MILLIS) {
                    callRuntimeController.execute(
                        MediaCallCommand.Connect(
                            callId = session.callId,
                            pairId = session.pairId,
                            remoteDisplayName = session.remoteDisplayName,
                            direction = session.direction,
                            localIdentity = session.participantIdentity.local,
                            connection = connection,
                            terminationSignal = terminationSignal,
                        ),
                    )
                }
                terminationSignal.throwIfRequested()
                AppResult.Success(Unit)
            }
        } catch (throwable: Throwable) {
            if (throwable is CallTerminationException) throw throwable
            if (throwable is TimeoutCancellationException) {
                logStage(session.callId, generation, "connect", "timeout", throwable = throwable)
                repositoryScope.launch {
                    terminateCall(
                        callId = session.callId,
                        terminalState = CallConnectionState.Failed(throwable.message),
                    )
                }
                return AppResult.Failure(throwable.asAppError())
            }
            if (throwable is CancellationException) throw throwable
            repositoryScope.launch {
                terminateCall(
                    callId = session.callId,
                    terminalState = CallConnectionState.Failed(throwable.message),
                )
            }
            AppResult.Failure(throwable.asAppError())
        } finally {
            withContext(NonCancellable) {
                operationMutex.withLock {
                    if (connectOperation?.id == id) connectOperation = null
                }
            }
        }
    }

    override suspend fun suspendForSystemCall(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult {
        val decision = operationMutex.withLock {
            val session = sessionState.value
            if (session == null || session.callId != request.callId) {
                return@withLock SuspendDecision.Ignore("call_mismatch")
            }
            if (
                session.connectionState == CallConnectionState.Terminating ||
                session.connectionState.isTerminal ||
                disconnectOperations.containsKey(session.callId)
            ) {
                return@withLock SuspendDecision.Ignore("termination_won")
            }
            if (
                session.connectionState == CallConnectionState.Preparing ||
                session.connectionState == CallConnectionState.Connecting
            ) {
                cancelSystemInterruptionLocked(clearOwner = true)
                return@withLock SuspendDecision.Terminate(session.callId)
            }
            if (
                session.connectionState != CallConnectionState.Connected &&
                session.connectionState != CallConnectionState.Reconnecting
            ) {
                return@withLock SuspendDecision.Ignore("call_not_suspendable")
            }

            val lifecycle = currentLifecycleGeneration
                ?: return@withLock SuspendDecision.Ignore("lifecycle_generation_unavailable")
            val media = mediaGeneration
                ?: return@withLock SuspendDecision.Ignore("media_generation_unavailable")
            val existing = systemInterruptionOwner
            if (existing != null && existing.callId == session.callId && existing.lifecycleGeneration == lifecycle) {
                if (request.telecomSequence < existing.latestTelecomSequence) {
                    return@withLock SuspendDecision.Ignore("stale_telecom_sequence")
                }
                if (
                    request.telecomSequence == existing.latestTelecomSequence &&
                    request.operationId != existing.operationId
                ) {
                    return@withLock SuspendDecision.Ignore("telecom_sequence_conflict")
                }
                if (request.operationId == existing.operationId && existing.completed) {
                    return@withLock SuspendDecision.Ignore("interruption_already_completed")
                }
                if (
                    request.operationId == existing.operationId &&
                    session.interruptionState.local is LocalCallInterruption.Resuming
                ) {
                    return@withLock SuspendDecision.Ignore("resume_already_started")
                }
            }

            val owner = if (
                existing != null &&
                existing.callId == session.callId &&
                existing.lifecycleGeneration == lifecycle &&
                existing.mediaGeneration == media &&
                existing.operationId == request.operationId
            ) {
                existing.latestTelecomSequence = maxOf(existing.latestTelecomSequence, request.telecomSequence)
                existing.resumeJob?.cancel()
                existing.resumeJob = null
                existing.completed = false
                existing
            } else {
                cancelSystemInterruptionLocked(clearOwner = true)
                SystemInterruptionOwner(
                    callId = session.callId,
                    lifecycleGeneration = lifecycle,
                    mediaGeneration = media,
                    operationId = request.operationId,
                    inactiveTelecomSequence = request.telecomSequence,
                    latestTelecomSequence = request.telecomSequence,
                    enableMicrophoneOnResume = session.localAudioState.shouldEnableMicrophoneOnResume(),
                ).also { created ->
                    created.telemetryOperation = runCatching {
                        interruptionTelemetry.startTransition(
                            CallInterruptionTransitionContext(
                                callId = created.callId,
                                operationId = created.operationId,
                                phase = CallInterruptionTransitionPhase.Suspend,
                                lifecycleGeneration = created.lifecycleGeneration,
                                mediaGeneration = created.mediaGeneration,
                            ),
                        )
                    }.getOrNull()
                    systemInterruptionOwner = created
                }
            }
            sessionState.emit(
                session.copy(
                    interruptionState = session.interruptionState.copy(
                        local = LocalCallInterruption.Suspending(owner.operationId),
                    ),
                ),
            )
            SuspendDecision.Apply(owner)
        }

        return when (decision) {
            is SuspendDecision.Ignore -> SystemCallInterruptionResult.Ignored(decision.reasonCode)
            is SuspendDecision.Terminate -> {
                scheduleInterruptionTermination(decision.callId)
                SystemCallInterruptionResult.TerminationScheduled
            }
            is SuspendDecision.Apply -> {
                val mediaResult = runCatching {
                    callRuntimeController.suspendForSystemCall(
                        MediaSystemCallSuspendRequest(
                            callId = decision.owner.callId,
                            expectedGeneration = decision.owner.mediaGeneration,
                            operationId = decision.owner.operationId,
                        ),
                    )
                }.getOrElse {
                    MediaSystemCallInterruptionResult.Degraded(
                        generation = decision.owner.mediaGeneration,
                        reasonCode = "runtime_suspend_exception",
                    )
                }
                runCatching {
                    decision.owner.telemetryOperation?.recordAttempt(
                        attempt = 0,
                        retriesRemaining = 0,
                        result = mediaResult.telemetryResult(),
                        reasonCode = mediaResult.reasonCodeOrNull(),
                    )
                }
                commitSuspendResult(decision.owner, mediaResult)
            }
        }
    }

    override suspend fun resumeAfterSystemCall(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult {
        val decision = operationMutex.withLock {
            val session = sessionState.value
            if (session == null || session.callId != request.callId) {
                return@withLock ResumeDecision.Ignore("call_mismatch")
            }
            if (
                session.connectionState == CallConnectionState.Terminating ||
                session.connectionState.isTerminal ||
                disconnectOperations.containsKey(session.callId)
            ) {
                return@withLock ResumeDecision.Ignore("termination_won")
            }
            val owner = systemInterruptionOwner
                ?: return@withLock ResumeDecision.Ignore("no_system_interruption")
            if (!owner.matchesCurrentLocked() || owner.operationId != request.operationId) {
                return@withLock ResumeDecision.Ignore("interruption_identity_mismatch")
            }
            if (request.telecomSequence < owner.latestTelecomSequence) {
                return@withLock ResumeDecision.Ignore("stale_telecom_sequence")
            }
            owner.latestTelecomSequence = request.telecomSequence
            if (owner.completed) return@withLock ResumeDecision.AlreadyApplied
            if (owner.resumeJob?.isActive == true) return@withLock ResumeDecision.RetryOwned

            owner.enforcementJob?.cancel()
            owner.enforcementJob = null
            runCatching { owner.telemetryOperation?.finish(result = "superseded") }
            owner.telemetryOperation = runCatching {
                interruptionTelemetry.startTransition(
                    CallInterruptionTransitionContext(
                        callId = owner.callId,
                        operationId = owner.operationId,
                        phase = CallInterruptionTransitionPhase.Resume,
                        lifecycleGeneration = owner.lifecycleGeneration,
                        mediaGeneration = owner.mediaGeneration,
                    ),
                )
            }.getOrNull()
            sessionState.emit(
                session.copy(
                    interruptionState = session.interruptionState.copy(
                        local = LocalCallInterruption.Resuming(
                            operationId = owner.operationId,
                            attemptsStarted = 0,
                            retriesRemaining = SYSTEM_RESUME_RETRY_COUNT,
                        ),
                    ),
                ),
            )
            ResumeDecision.Attempt(owner)
        }

        return when (decision) {
            is ResumeDecision.Ignore -> SystemCallInterruptionResult.Ignored(decision.reasonCode)
            ResumeDecision.AlreadyApplied -> SystemCallInterruptionResult.Applied
            ResumeDecision.RetryOwned -> SystemCallInterruptionResult.RetryScheduled
            is ResumeDecision.Attempt -> {
                val mediaResult = attemptMediaResume(decision.owner, attemptIndex = 0)
                commitInitialResumeResult(decision.owner, mediaResult)
            }
        }
    }

    private suspend fun commitSuspendResult(
        owner: SystemInterruptionOwner,
        result: MediaSystemCallInterruptionResult,
    ): SystemCallInterruptionResult {
        var terminateCallId: String? = null
        val outcome = operationMutex.withLock {
            if (!owner.matchesCurrentLocked()) {
                return@withLock SystemCallInterruptionResult.Ignored("stale_suspend_result")
            }
            val session = requireNotNull(sessionState.value)
            when (result) {
                is MediaSystemCallInterruptionResult.Applied -> {
                    owner.enforcementJob?.cancel()
                    owner.enforcementJob = null
                    sessionState.emit(session.withLocalInterruptionSuspended(owner.operationId, degraded = false))
                    owner.finishTelemetry(result = "applied")
                    SystemCallInterruptionResult.Applied
                }
                is MediaSystemCallInterruptionResult.TerminalFailure -> {
                    terminateCallId = owner.callId
                    owner.enforcementJob?.cancel()
                    owner.enforcementJob = null
                    owner.finishTelemetry(
                        result = "terminal_failure",
                        reasonCode = result.reasonCode,
                        finalFailure = true,
                    )
                    SystemCallInterruptionResult.TerminationScheduled
                }
                is MediaSystemCallInterruptionResult.Degraded,
                is MediaSystemCallInterruptionResult.Failed,
                is MediaSystemCallInterruptionResult.PausedReconnecting,
                is MediaSystemCallInterruptionResult.Stale,
                -> {
                    val reasonCode = result.reasonCodeOr("suspend_degraded")
                    sessionState.emit(session.withLocalInterruptionSuspended(owner.operationId, degraded = true))
                    ensureSuspendEnforcementLocked(owner)
                    owner.finishTelemetry(result = "degraded", reasonCode = reasonCode)
                    SystemCallInterruptionResult.Degraded(reasonCode)
                }
            }
        }
        terminateCallId?.let(::scheduleInterruptionTermination)
        return outcome
    }

    private suspend fun commitInitialResumeResult(
        owner: SystemInterruptionOwner,
        result: MediaSystemCallInterruptionResult,
    ): SystemCallInterruptionResult {
        var terminateCallId: String? = null
        val outcome = operationMutex.withLock {
            if (!owner.matchesCurrentLocked()) {
                return@withLock SystemCallInterruptionResult.Ignored("stale_resume_result")
            }
            val session = requireNotNull(sessionState.value)
            if (
                session.connectionState == CallConnectionState.Reconnecting ||
                result is MediaSystemCallInterruptionResult.PausedReconnecting
            ) {
                sessionState.emit(
                    session.withLocalInterruptionResuming(
                        operationId = owner.operationId,
                        attemptsStarted = 0,
                        retriesRemaining = SYSTEM_RESUME_RETRY_COUNT,
                    ),
                )
                ensureResumeOwnerLocked(owner, nextAttemptIndex = 0)
                return@withLock SystemCallInterruptionResult.RetryScheduled
            }
            when (result) {
                is MediaSystemCallInterruptionResult.Applied -> {
                    completeSystemInterruptionLocked(owner)
                    SystemCallInterruptionResult.Applied
                }
                is MediaSystemCallInterruptionResult.TerminalFailure,
                is MediaSystemCallInterruptionResult.Stale,
                -> {
                    owner.resumeJob = null
                    owner.finishTelemetry(
                        result = "terminal_failure",
                        reasonCode = result.reasonCodeOr("terminal_resume_failure"),
                        finalFailure = true,
                    )
                    terminateCallId = owner.callId
                    SystemCallInterruptionResult.TerminationScheduled
                }
                is MediaSystemCallInterruptionResult.Degraded,
                is MediaSystemCallInterruptionResult.Failed,
                -> {
                    sessionState.emit(
                        session.withLocalInterruptionResuming(
                            operationId = owner.operationId,
                            attemptsStarted = 1,
                            retriesRemaining = SYSTEM_RESUME_RETRY_COUNT,
                        ),
                    )
                    ensureResumeOwnerLocked(owner, nextAttemptIndex = 1)
                    SystemCallInterruptionResult.RetryScheduled
                }
                is MediaSystemCallInterruptionResult.PausedReconnecting -> error("Handled above")
            }
        }
        terminateCallId?.let(::scheduleInterruptionTermination)
        return outcome
    }

    private suspend fun attemptMediaResume(
        owner: SystemInterruptionOwner,
        attemptIndex: Int,
    ): MediaSystemCallInterruptionResult {
        val result = runCatching {
            callRuntimeController.resumeAfterSystemCall(
                MediaSystemCallResumeRequest(
                    callId = owner.callId,
                    expectedGeneration = owner.mediaGeneration,
                    operationId = owner.operationId,
                    enableMicrophone = owner.enableMicrophoneOnResume,
                ),
            )
        }.getOrElse {
            MediaSystemCallInterruptionResult.Failed(
                generation = owner.mediaGeneration,
                reasonCode = "runtime_resume_exception",
            )
        }
        runCatching {
            owner.telemetryOperation?.recordAttempt(
                attempt = attemptIndex,
                retriesRemaining = (SYSTEM_RESUME_RETRY_COUNT - attemptIndex).coerceAtLeast(0),
                result = result.telemetryResult(),
                reasonCode = result.reasonCodeOrNull(),
            )
        }
        return result
    }

    private fun ensureSuspendEnforcementLocked(owner: SystemInterruptionOwner) {
        if (owner.enforcementJob?.isActive == true) return
        val job = repositoryScope.launch(start = CoroutineStart.LAZY) {
            runSuspendEnforcement(owner)
        }
        owner.enforcementJob = job
        job.start()
    }

    private suspend fun runSuspendEnforcement(owner: SystemInterruptionOwner) {
        val job = currentCoroutineContext()[Job]
        try {
            while (true) {
                delay(SYSTEM_SUSPEND_ENFORCEMENT_INTERVAL_MILLIS)
                val shouldAttempt = operationMutex.withLock {
                    owner.matchesCurrentLocked() &&
                        sessionState.value?.interruptionState?.local is LocalCallInterruption.Suspended
                }
                if (!shouldAttempt) return

                val result = runCatching {
                    callRuntimeController.suspendForSystemCall(
                        MediaSystemCallSuspendRequest(
                            callId = owner.callId,
                            expectedGeneration = owner.mediaGeneration,
                            operationId = owner.operationId,
                        ),
                    )
                }.getOrElse {
                    MediaSystemCallInterruptionResult.Degraded(
                        generation = owner.mediaGeneration,
                        reasonCode = "runtime_suspend_enforcement_exception",
                    )
                }

                var terminateCallId: String? = null
                val completed = operationMutex.withLock {
                    if (!owner.matchesCurrentLocked()) return@withLock true
                    val session = requireNotNull(sessionState.value)
                    when (result) {
                        is MediaSystemCallInterruptionResult.Applied -> {
                            sessionState.emit(
                                session.withLocalInterruptionSuspended(owner.operationId, degraded = false),
                            )
                            true
                        }
                        is MediaSystemCallInterruptionResult.TerminalFailure -> {
                            owner.enforcementJob = null
                            terminateCallId = owner.callId
                            true
                        }
                        is MediaSystemCallInterruptionResult.Degraded,
                        is MediaSystemCallInterruptionResult.Failed,
                        is MediaSystemCallInterruptionResult.PausedReconnecting,
                        is MediaSystemCallInterruptionResult.Stale,
                        -> {
                            sessionState.emit(
                                session.withLocalInterruptionSuspended(owner.operationId, degraded = true),
                            )
                            false
                        }
                    }
                }
                terminateCallId?.let(::scheduleInterruptionTermination)
                if (completed) return
            }
        } finally {
            operationMutex.withLock {
                if (owner.enforcementJob === job) owner.enforcementJob = null
            }
        }
    }

    private fun ensureResumeOwnerLocked(
        owner: SystemInterruptionOwner,
        nextAttemptIndex: Int,
    ) {
        if (owner.resumeJob?.isActive == true) return
        val job = repositoryScope.launch(start = CoroutineStart.LAZY) {
            runResumeAttempts(owner, nextAttemptIndex)
        }
        owner.resumeJob = job
        job.start()
    }

    private suspend fun runResumeAttempts(
        owner: SystemInterruptionOwner,
        firstAttemptIndex: Int,
    ) {
        val job = currentCoroutineContext()[Job]
        var attemptIndex = firstAttemptIndex
        var delayBeforeAttempt = attemptIndex > 0
        try {
            while (attemptIndex <= SYSTEM_RESUME_RETRY_COUNT) {
                if (delayBeforeAttempt) {
                    delay(SYSTEM_RESUME_RETRY_INTERVAL_MILLIS)
                    delayBeforeAttempt = false
                }

                when (awaitResumeReadiness(owner)) {
                    ResumeReadiness.Stop -> return
                    ResumeReadiness.Attempt -> Unit
                }

                val started = operationMutex.withLock {
                    if (!owner.matchesCurrentLocked()) return@withLock false
                    val session = requireNotNull(sessionState.value)
                    if (session.connectionState != CallConnectionState.Connected) {
                        return@withLock false
                    }
                    sessionState.emit(
                        session.withLocalInterruptionResuming(
                            operationId = owner.operationId,
                            attemptsStarted = attemptIndex + 1,
                            retriesRemaining = (SYSTEM_RESUME_RETRY_COUNT - attemptIndex).coerceAtLeast(0),
                        ),
                    )
                    true
                }
                if (!started) continue

                val result = attemptMediaResume(owner, attemptIndex)
                var terminateCallId: String? = null
                val action = operationMutex.withLock {
                    if (!owner.matchesCurrentLocked()) return@withLock ResumeLoopAction.Stop
                    val session = requireNotNull(sessionState.value)
                    if (
                        session.connectionState == CallConnectionState.Reconnecting ||
                        result is MediaSystemCallInterruptionResult.PausedReconnecting
                    ) {
                        val retriesBeforeThisAttempt = if (attemptIndex == 0) {
                            SYSTEM_RESUME_RETRY_COUNT
                        } else {
                            SYSTEM_RESUME_RETRY_COUNT - attemptIndex + 1
                        }
                        sessionState.emit(
                            session.withLocalInterruptionResuming(
                                operationId = owner.operationId,
                                attemptsStarted = attemptIndex,
                                retriesRemaining = retriesBeforeThisAttempt,
                            ),
                        )
                        return@withLock ResumeLoopAction.RetrySameAttempt
                    }

                    when (result) {
                        is MediaSystemCallInterruptionResult.Applied -> {
                            completeSystemInterruptionLocked(owner)
                            ResumeLoopAction.Stop
                        }
                        is MediaSystemCallInterruptionResult.TerminalFailure,
                        is MediaSystemCallInterruptionResult.Stale,
                        -> {
                            owner.resumeJob = null
                            owner.finishTelemetry(
                                result = "terminal_failure",
                                reasonCode = result.reasonCodeOr("terminal_resume_failure"),
                                finalFailure = true,
                            )
                            terminateCallId = owner.callId
                            ResumeLoopAction.Stop
                        }
                        is MediaSystemCallInterruptionResult.Degraded,
                        is MediaSystemCallInterruptionResult.Failed,
                        -> {
                            if (attemptIndex >= SYSTEM_RESUME_RETRY_COUNT) {
                                owner.resumeJob = null
                                owner.finishTelemetry(
                                    result = "exhausted",
                                    reasonCode = result.reasonCodeOr("resume_retries_exhausted"),
                                    finalFailure = true,
                                )
                                terminateCallId = owner.callId
                                ResumeLoopAction.Stop
                            } else {
                                sessionState.emit(
                                    session.withLocalInterruptionResuming(
                                        operationId = owner.operationId,
                                        attemptsStarted = attemptIndex + 1,
                                        retriesRemaining = SYSTEM_RESUME_RETRY_COUNT - attemptIndex,
                                    ),
                                )
                                ResumeLoopAction.NextAttempt
                            }
                        }
                        is MediaSystemCallInterruptionResult.PausedReconnecting -> error("Handled above")
                    }
                }
                terminateCallId?.let(::scheduleInterruptionTermination)
                when (action) {
                    ResumeLoopAction.Stop -> return
                    ResumeLoopAction.RetrySameAttempt -> Unit
                    ResumeLoopAction.NextAttempt -> {
                        attemptIndex += 1
                        delayBeforeAttempt = true
                    }
                }
            }
        } finally {
            operationMutex.withLock {
                if (owner.resumeJob === job) owner.resumeJob = null
            }
        }
    }

    private suspend fun awaitResumeReadiness(owner: SystemInterruptionOwner): ResumeReadiness {
        while (true) {
            val readiness = operationMutex.withLock {
                if (!owner.matchesCurrentLocked()) return@withLock ResumeReadiness.Stop
                when (sessionState.value?.connectionState) {
                    CallConnectionState.Connected -> ResumeReadiness.Attempt
                    CallConnectionState.Reconnecting -> null
                    else -> ResumeReadiness.Stop
                }
            }
            if (readiness != null) return readiness
            sessionState.first { session ->
                session?.callId != owner.callId ||
                    session.connectionState != CallConnectionState.Reconnecting ||
                    session.interruptionState.local !is LocalCallInterruption.Resuming
            }
        }
    }

    private fun scheduleInterruptionTermination(callId: String) {
        repositoryScope.launch {
            terminateCall(
                callId = callId,
                terminalState = CallConnectionState.Disconnected,
            )
        }
    }

    private fun SystemInterruptionOwner.matchesCurrentLocked(): Boolean {
        val session = sessionState.value ?: return false
        return systemInterruptionOwner === this &&
            session.callId == callId &&
            currentLifecycleGeneration == lifecycleGeneration &&
            mediaGeneration == this.mediaGeneration &&
            session.connectionState != CallConnectionState.Terminating &&
            !session.connectionState.isTerminal &&
            !disconnectOperations.containsKey(callId)
    }

    private suspend fun completeSystemInterruptionLocked(owner: SystemInterruptionOwner) {
        if (!owner.matchesCurrentLocked()) return
        owner.enforcementJob?.cancel()
        owner.enforcementJob = null
        owner.resumeJob = null
        owner.completed = true
        owner.finishTelemetry(result = "applied")
        val session = requireNotNull(sessionState.value)
        sessionState.emit(
            session.copy(
                interruptionState = session.interruptionState.copy(local = LocalCallInterruption.None),
            ),
        )
    }

    private suspend fun cancelSystemInterruptionLocked(clearOwner: Boolean) {
        val owner = systemInterruptionOwner
        owner?.enforcementJob?.cancel()
        owner?.resumeJob?.cancel()
        owner?.finishTelemetry(result = "cancelled")
        owner?.enforcementJob = null
        owner?.resumeJob = null
        if (clearOwner) systemInterruptionOwner = null
        val session = sessionState.value ?: return
        if (session.interruptionState.local != LocalCallInterruption.None) {
            sessionState.emit(
                session.copy(
                    interruptionState = session.interruptionState.copy(local = LocalCallInterruption.None),
                ),
            )
        }
    }

    override suspend fun disconnectCall(callId: String): AppResult<Unit> = terminateCall(
        callId = callId,
        terminalState = CallConnectionState.Disconnected,
    )

    private suspend fun terminateCall(
        callId: String,
        terminalState: CallConnectionState,
    ): AppResult<Unit> {
        var serverEndGeneration = 0L
        var shouldEnqueueServerEnd = false
        val operation = operationMutex.withLock {
            disconnectOperations[callId]?.let { currentOperation ->
                serverEndGeneration = currentOperation.generation
                shouldEnqueueServerEnd = true
                return@withLock currentOperation.deferred
            }

            val session = sessionState.value
            if (session == null) {
                prepareOperation?.deferred?.cancel(CancellationException("Call termination requested"))
                serverEndGeneration = ++lifecycleGeneration
                shouldEnqueueServerEnd = true
                return@withLock null
            }
            if (session.callId != callId) {
                serverEndGeneration = ++lifecycleGeneration
                shouldEnqueueServerEnd = true
                return@withLock null
            }
            if (session.connectionState.isTerminal) {
                serverEndGeneration = ++lifecycleGeneration
                shouldEnqueueServerEnd = true
                return@withLock null
            }

            val pendingConnect = connectOperation
                ?.takeIf { it.callId == session.callId }
            pendingConnect?.terminationSignal?.request()
            val generation = pendingConnect?.generation ?: ++lifecycleGeneration
            logStage(session.callId, generation, "termination.request", "begin")

            mediaConnection = null
            mediaGeneration = null
            currentLifecycleGeneration = null
            cancelSystemInterruptionLocked(clearOwner = true)
            callStatusSynchronizer.stop()
            if (session.connectionState != CallConnectionState.Terminating) {
                sessionState.emit(
                    session.copy(
                        connectionState = CallConnectionState.Terminating,
                        localAudioState = LocalAudioState.Disabled,
                        uiSnapshot = session.uiSnapshot.copy(remoteParticipantConnected = false),
                    ),
                )
            }

            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                disconnectSession(
                    session = session,
                    requestedTerminalState = terminalState,
                    generation = generation,
                    pendingConnect = pendingConnect?.deferred,
                )
            }
            disconnectOperations[session.callId] = DisconnectOperation(
                callId = session.callId,
                generation = generation,
                deferred = deferred,
            )
            disconnectingCallIdState.value = session.callId
            serverEndGeneration = generation
            shouldEnqueueServerEnd = true
            deferred
        }

        // Server reconciliation is deliberately independent from local teardown. Start it
        // before any synchronous/native disconnect stage can suspend or time out, and also
        // start it for idempotent/stale calls that no longer have a local session.
        if (shouldEnqueueServerEnd) {
            enqueueServerEnd(callId, serverEndGeneration)
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
        generation: Long,
        pendingConnect: Deferred<AppResult<Unit>>?,
    ): AppResult<Unit> {
        var failure: Throwable? = null
        var cancellation: CancellationException? = null
        val terminationStartedAt = System.nanoTime()
        try {
            withTimeout(CALL_TERMINATION_TIMEOUT_MILLIS) {
                failure = performDisconnectSession(
                    session = session,
                    requestedTerminalState = requestedTerminalState,
                    generation = generation,
                    pendingConnect = pendingConnect,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            failure = failure?.also { it.addSuppressed(timeout) } ?: timeout
            logStage(
                session.callId,
                generation,
                "termination",
                "timeout",
                terminationStartedAt,
                timeout,
            )
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        } finally {
            withContext(NonCancellable) {
                finalizeDisconnectSession(
                    session = session,
                    requestedTerminalState = requestedTerminalState,
                    generation = generation,
                    failure = failure ?: cancellation,
                )
            }
        }

        cancellation?.let { throw it }
        return failure?.let { AppResult.Failure(it.asAppError()) } ?: AppResult.Success(Unit)
    }

    private suspend fun performDisconnectSession(
        session: CallSession,
        requestedTerminalState: CallConnectionState,
        generation: Long,
        pendingConnect: Deferred<AppResult<Unit>>?,
    ): Throwable? {
        var failure: Throwable? = null

        // Do not await the connect owner here. The termination signal has already
        // been requested; local teardown owns the runtime and stale connect
        // completions are filtered by callId/generation.

        val localStartedAt = System.nanoTime()
        logStage(session.callId, generation, "local.release", "begin")
        try {
            callRuntimeController.execute(MediaCallCommand.Disconnect(session.callId))
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            failure = throwable
            runCatching { callRuntimeController.releaseResources() }
                .exceptionOrNull()
                ?.let { cleanupFailure -> failure?.addSuppressed(cleanupFailure) }
        }
        logStage(
            session.callId,
            generation,
            "local.release",
            if (failure == null) "end" else "error",
            localStartedAt,
        )
        operationMutex.withLock {
            val current = sessionState.value
            if (current?.callId == session.callId) {
                val localTerminalState = failure
                    ?.let { CallConnectionState.Failed(it.message) }
                    ?: requestedTerminalState
                sessionState.emit(
                    current.copy(
                        connectionState = localTerminalState,
                        localAudioState = LocalAudioState.Disabled,
                        uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                    ),
                )
            }
        }

        return failure
    }

    /** Server synchronization is application-scoped and never part of local call termination. */
    private suspend fun enqueueServerEnd(callId: String, generation: Long) {
        operationMutex.withLock {
            if (callId in serverEndedCallIds) return@withLock
            val existing = serverEndJobs[callId]
            if (existing != null && existing.isActive) return@withLock
            serverEndJobs[callId] = repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var backoffMillis = SERVER_END_INITIAL_BACKOFF_MILLIS
                var completed = false
                try {
                    while (true) {
                        val startedAt = System.nanoTime()
                        try {
                            logStage(callId, generation, "server.end", "begin")
                            api.endCall(callId)
                            logStage(callId, generation, "server.end", "end", startedAt)
                            completed = true
                            break
                        } catch (throwable: Throwable) {
                            if (throwable is CancellationException) throw throwable
                            logStage(callId, generation, "server.end", "retry", startedAt, throwable)
                            delay(backoffMillis)
                            backoffMillis = (backoffMillis * 2)
                                .coerceAtMost(SERVER_END_MAX_BACKOFF_MILLIS)
                        }
                    }
                } finally {
                    operationMutex.withLock {
                        if (completed) serverEndedCallIds.add(callId)
                        if (serverEndJobs[callId] === currentCoroutineContext()[Job]) {
                            serverEndJobs.remove(callId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun finalizeDisconnectSession(
        session: CallSession,
        requestedTerminalState: CallConnectionState,
        generation: Long,
        failure: Throwable?,
    ) {
        operationMutex.withLock {
            disconnectOperations[session.callId]
                ?.takeIf { it.generation == generation }
                ?.let { disconnectOperations.remove(session.callId) }
            val current = sessionState.value
            if (current?.callId == session.callId) {
                val finalState = if (current.connectionState.isTerminal) {
                    current.connectionState
                } else {
                    failure?.let { CallConnectionState.Failed(it.message) } ?: requestedTerminalState
                }
                sessionState.emit(
                    current.copy(
                        connectionState = finalState,
                        localAudioState = LocalAudioState.Disabled,
                        uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                    ),
                )
                logStage(session.callId, generation, "terminal.emit", "end")
            }
            if (disconnectingCallIdState.value == session.callId) {
                disconnectingCallIdState.value = null
            }
        }
    }

    override suspend fun setMuted(muted: Boolean): AppResult<Unit> = operationMutex.withLock {
        val session = sessionState.value
            ?: return@withLock AppResult.Failure(AppError.Validation("No call session"))
        if (session.connectionState != CallConnectionState.Connected) {
            return@withLock AppResult.Failure(AppError.Validation("Call media is not connected"))
        }
        if (session.interruptionState.local != LocalCallInterruption.None) {
            return@withLock AppResult.Failure(AppError.Validation("Call media is suspended by a system call"))
        }
        val previousAudioState = session.localAudioState
        if (muted && previousAudioState == LocalAudioState.Muted) {
            return@withLock AppResult.Success(Unit)
        }
        if (!muted && previousAudioState == LocalAudioState.Enabled) {
            return@withLock AppResult.Success(Unit)
        }
        if (previousAudioState != LocalAudioState.Enabled && previousAudioState != LocalAudioState.Muted) {
            return@withLock AppResult.Failure(AppError.Validation("Microphone state is not ready"))
        }
        sessionState.emit(
            session.copy(
                localAudioState = if (muted) LocalAudioState.Muting else LocalAudioState.Unmuting,
            ),
        )
        appResult(
            onFailure = {
                val restoredSession = requireNotNull(sessionState.value ?: session)
                    .copy(localAudioState = previousAudioState)
                sessionState.emit(restoredSession)
            },
        ) {
            callRuntimeController.execute(
                MediaCallCommand.SetMuted(
                    callId = session.callId,
                    muted = muted,
                ),
            )
            val updatedSession = requireNotNull(sessionState.value ?: session).copy(
                localAudioState = if (muted) LocalAudioState.Muted else LocalAudioState.Enabled,
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
        if (session.interruptionState.local != LocalCallInterruption.None) {
            return@withLock AppResult.Failure(AppError.Validation("Audio route is locked during a system call"))
        }
        appResult {
            callRuntimeController.selectAudioRoute(route)
        }
    }

    private fun startCallStatusSync(callId: String) {
        callStatusSynchronizer.start(callId) { callStatus ->
            val callEnded = operationMutex.withLock {
                val latestSession = sessionState.value
                if (latestSession == null || latestSession.callId != callId) return@withLock false
                val syncedSession = latestSession.copy(
                    recordingState = callStatus.recordingStatus.toRecordingState(),
                    timing = callStatus.toCallTiming(previous = latestSession.timing),
                )
                if (callStatus.state.equals("ended", ignoreCase = true)) {
                    sessionState.emit(syncedSession)
                    return@withLock true
                }
                if (syncedSession != latestSession) {
                    sessionState.emit(syncedSession)
                }
                false
            }
            if (!callEnded) return@start true

            // Route status-driven termination through the same call-scoped owner as UI,
            // notification, Telecom, and media events. This records the termination signal
            // before waiting for runtime cleanup and keeps slow teardown outside operationMutex.
            terminateCall(
                callId = callId,
                terminalState = CallConnectionState.Disconnected,
            )
            false
        }
    }

    private fun logStage(
        callId: String,
        generation: Long,
        phase: String,
        event: String,
        startedAtNanos: Long? = null,
        throwable: Throwable? = null,
    ) {
        val elapsedMillis = startedAtNanos?.let { (System.nanoTime() - it) / NANOS_PER_MILLI }
        val message = buildString {
            append("callId=").append(callId)
            append(" generation=").append(generation)
            append(" phase=").append(phase)
            append(" event=").append(event)
            elapsedMillis?.let { append(" elapsedMs=").append(it) }
        }
        if (throwable == null) logger.d(LOG_TAG, message) else logger.e(LOG_TAG, throwable, message)
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
        const val LOG_TAG = "CallLifecycle"
        const val NANOS_PER_MILLI = 1_000_000L
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val CALL_TERMINATION_TIMEOUT_MILLIS = 5_000L
        const val SERVER_END_INITIAL_BACKOFF_MILLIS = 1_000L
        const val SERVER_END_MAX_BACKOFF_MILLIS = 60_000L
        const val SYSTEM_SUSPEND_ENFORCEMENT_INTERVAL_MILLIS = 1_000L
        const val SYSTEM_RESUME_RETRY_INTERVAL_MILLIS = 1_000L
        const val SYSTEM_RESUME_RETRY_COUNT = 20
    }
}

private sealed interface SuspendDecision {
    data class Apply(val owner: SystemInterruptionOwner) : SuspendDecision
    data class Terminate(val callId: String) : SuspendDecision
    data class Ignore(val reasonCode: String) : SuspendDecision
}

private sealed interface ResumeDecision {
    data class Attempt(val owner: SystemInterruptionOwner) : ResumeDecision
    data class Ignore(val reasonCode: String) : ResumeDecision
    data object AlreadyApplied : ResumeDecision
    data object RetryOwned : ResumeDecision
}

private enum class ResumeReadiness {
    Attempt,
    Stop,
}

private enum class ResumeLoopAction {
    NextAttempt,
    RetrySameAttempt,
    Stop,
}

private data class SystemInterruptionOwner(
    val callId: String,
    val lifecycleGeneration: Long,
    val mediaGeneration: Long,
    val operationId: String,
    val inactiveTelecomSequence: Long,
    var latestTelecomSequence: Long,
    val enableMicrophoneOnResume: Boolean,
    var enforcementJob: Job? = null,
    var resumeJob: Job? = null,
    var completed: Boolean = false,
    var telemetryOperation: CallInterruptionTelemetryOperation? = null,
)

private data class DisconnectOperation(
    val callId: String,
    val generation: Long,
    val deferred: Deferred<AppResult<Unit>>,
)

private data class PrepareOperation(
    val id: Long,
    val request: CallPreparationRequest,
    val deferred: Deferred<AppResult<CallSession>>,
)

private data class ConnectOperation(
    val id: Long,
    val generation: Long,
    val callId: String,
    val terminationSignal: CallTerminationSignal,
    val deferred: Deferred<AppResult<Unit>>,
)

private fun LocalAudioState.shouldEnableMicrophoneOnResume(): Boolean = when (this) {
    LocalAudioState.Enabled,
    LocalAudioState.Unmuting,
    LocalAudioState.Enabling,
    -> true
    LocalAudioState.Disabled,
    LocalAudioState.Muting,
    LocalAudioState.Muted,
    is LocalAudioState.Error,
    -> false
}

private fun CallSession.withLocalInterruptionSuspended(
    operationId: String,
    degraded: Boolean,
): CallSession = copy(
    interruptionState = interruptionState.copy(
        local = LocalCallInterruption.Suspended(
            operationId = operationId,
            degraded = degraded,
        ),
    ),
)

private fun CallSession.withLocalInterruptionResuming(
    operationId: String,
    attemptsStarted: Int,
    retriesRemaining: Int,
): CallSession = copy(
    interruptionState = interruptionState.copy(
        local = LocalCallInterruption.Resuming(
            operationId = operationId,
            attemptsStarted = attemptsStarted,
            retriesRemaining = retriesRemaining,
        ),
    ),
)

private fun MediaSystemCallInterruptionResult.reasonCodeOr(fallback: String): String = when (this) {
    is MediaSystemCallInterruptionResult.Degraded -> reasonCode
    is MediaSystemCallInterruptionResult.Failed -> reasonCode
    is MediaSystemCallInterruptionResult.TerminalFailure -> reasonCode
    is MediaSystemCallInterruptionResult.Stale -> reasonCode
    is MediaSystemCallInterruptionResult.Applied,
    is MediaSystemCallInterruptionResult.PausedReconnecting,
    -> fallback
}

private fun MediaSystemCallInterruptionResult.reasonCodeOrNull(): String? = when (this) {
    is MediaSystemCallInterruptionResult.Degraded -> reasonCode
    is MediaSystemCallInterruptionResult.Failed -> reasonCode
    is MediaSystemCallInterruptionResult.TerminalFailure -> reasonCode
    is MediaSystemCallInterruptionResult.Stale -> reasonCode
    is MediaSystemCallInterruptionResult.Applied,
    is MediaSystemCallInterruptionResult.PausedReconnecting,
    -> null
}

private fun MediaSystemCallInterruptionResult.telemetryResult(): String = when (this) {
    is MediaSystemCallInterruptionResult.Applied -> "applied"
    is MediaSystemCallInterruptionResult.Degraded -> "degraded"
    is MediaSystemCallInterruptionResult.PausedReconnecting -> "paused_reconnecting"
    is MediaSystemCallInterruptionResult.Failed -> "failed"
    is MediaSystemCallInterruptionResult.TerminalFailure -> "terminal_failure"
    is MediaSystemCallInterruptionResult.Stale -> "stale"
}

private fun SystemInterruptionOwner.finishTelemetry(
    result: String,
    reasonCode: String? = null,
    finalFailure: Boolean = false,
) {
    runCatching {
        telemetryOperation?.finish(
            result = result,
            reasonCode = reasonCode,
            finalFailure = finalFailure,
        )
    }
    telemetryOperation = null
}
