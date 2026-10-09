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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
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
import life.fxs.purr.core.common.NetworkAvailability
import kotlin.random.Random
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.network.httpStatusCode
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
import life.fxs.purr.domain.call.model.canToggleMute
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
    private val networkAvailability: NetworkAvailability = NetworkAvailability.None,
) : CallRepository {
    /** Replaceable by tests to make rejoin backoff jitter deterministic. */
    internal var rejoinRandom: Random = Random.Default
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
    private var rejoinOwner: RejoinOwner? = null
    /** Provider generation of the lost room; events at or below it are stale while rejoining. */
    private var lostMediaGeneration: Long? = null
    private var recordingConsent: Boolean = false
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
                val termination = operationMutex.withLock {
                    val current = sessionState.value
                    // A delayed event from a previous media generation must not mutate a new call.
                    if (current == null || current.callId != mediaEvent.callId) return@withLock null
                    val expectedGeneration = mediaGeneration
                    if (expectedGeneration != null && expectedGeneration != mediaEvent.generation) {
                        return@withLock null
                    }
                    // Teardown clears mediaGeneration. Late buffered callbacks must not
                    // use that empty generation slot to revive an ended local session.
                    if (current.connectionState.isTerminal ||
                        current.connectionState == CallConnectionState.Terminating
                    ) {
                        return@withLock null
                    }
                    // A lost room clears mediaGeneration too; its late callbacks must not
                    // be accepted as the replacement room.
                    val lostGeneration = lostMediaGeneration
                    if (expectedGeneration == null && lostGeneration != null &&
                        mediaEvent.generation <= lostGeneration
                    ) {
                        return@withLock null
                    }
                    when (mediaEvent) {
                        is MediaCallEvent.ConnectionLost ->
                            return@withLock handleConnectionLostLocked(current, mediaEvent)
                        is MediaCallEvent.Connected,
                        is MediaCallEvent.Reconnecting,
                        is MediaCallEvent.Reconnected,
                        -> {
                            mediaGeneration = mediaEvent.generation
                            lostMediaGeneration = null
                        }
                        is MediaCallEvent.Disconnected,
                        is MediaCallEvent.Failed,
                        -> {
                            // Register teardown atomically with the event so a new call cannot
                            // replace this session before its runtime is released. The teardown
                            // itself runs outside this collector.
                            val terminalState = when (mediaEvent) {
                                is MediaCallEvent.Failed -> CallConnectionState.Failed(mediaEvent.reason)
                                else -> CallConnectionState.Disconnected
                            }
                            return@withLock beginTerminationLocked(current.callId, terminalState)
                        }
                        else -> Unit
                    }

                    val synchronizedSession = callMediaEventReducer.reduce(current, mediaEvent)
                        ?: return@withLock null
                    sessionState.emit(synchronizedSession)
                    null
                }
                if (termination != null) {
                    repositoryScope.launch { completeTermination(mediaEvent.callId, termination) }
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
        var immediateResult: AppResult<CallSession>? = null
        val operation = operationMutex.withLock {
            val inFlight = prepareOperation
            if (inFlight != null) {
                if (inFlight.request == request) return@withLock inFlight.deferred
                immediateResult = AppResult.Failure(AppError.Validation("A different call is being prepared"))
                return@withLock null
            }
            // A terminating call no longer blocks the next one; its teardown is call-scoped.
            val existing = sessionState.value
            if (existing != null && existing.connectionState.isResumable) {
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
                cancelSystemInterruptionLocked()
                mediaConnection = created.mediaConnection
                mediaGeneration = null
                lostMediaGeneration = null
                rejoinOwner = null
                recordingConsent = request.recordingConsent
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
            cancelSystemInterruptionLocked()
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
        } catch (_: CallTerminationException) {
            // Termination won the race. Its owner publishes the terminal state; the connect
            // caller is not cancelled and must receive a result rather than a cancellation.
            AppResult.Failure(AppError.Validation("Call was terminated while connecting"))
        } catch (throwable: Throwable) {
            if (throwable is CancellationException && throwable !is TimeoutCancellationException) {
                throw throwable
            }
            logStage(session.callId, generation, "connect", "error", throwable = throwable)
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
    ): SystemCallInterruptionResult = suspendForSystemCall(request, microphoneOverride = null)

    private suspend fun suspendForSystemCall(
        request: SystemCallInterruptionRequest,
        microphoneOverride: Boolean?,
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
            val rejoining = rejoinOwner?.takeIf { it.callId == session.callId }
            if (rejoining != null) {
                // The next room opens with the physical gate closed; the owner replays this
                // request once the replacement media generation is committed.
                val pending = rejoining.pendingInterruption
                if (pending == null || pending.operationId != request.operationId) {
                    rejoining.pendingInterruption = PendingInterruption(
                        operationId = request.operationId,
                        telecomSequence = request.telecomSequence,
                        enableMicrophoneOnResume = pending?.enableMicrophoneOnResume
                            ?: rejoining.microphoneEnabled,
                        resumeRequested = false,
                    )
                } else {
                    pending.telecomSequence = maxOf(pending.telecomSequence, request.telecomSequence)
                }
                if (session.interruptionState.local == LocalCallInterruption.None) {
                    sessionState.emit(
                        session.copy(
                            interruptionState = session.interruptionState.copy(
                                local = LocalCallInterruption.Suspending(request.operationId),
                            ),
                        ),
                    )
                }
                return@withLock SuspendDecision.Deferred
            }
            if (
                session.connectionState == CallConnectionState.Preparing ||
                session.connectionState == CallConnectionState.Connecting
            ) {
                cancelSystemInterruptionLocked()
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
                // Immediately followed by the Suspending publication below.
                cancelSystemInterruptionLocked(resetPresentation = false)
                SystemInterruptionOwner(
                    callId = session.callId,
                    lifecycleGeneration = lifecycle,
                    mediaGeneration = media,
                    operationId = request.operationId,
                    latestTelecomSequence = request.telecomSequence,
                    enableMicrophoneOnResume = microphoneOverride
                        ?: session.localAudioState.shouldEnableMicrophoneOnResume(),
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
            SuspendDecision.Deferred -> SystemCallInterruptionResult.RetryScheduled
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
            val rejoining = rejoinOwner?.takeIf { it.callId == session.callId }
            if (rejoining != null) {
                val pending = rejoining.pendingInterruption
                if (pending == null || pending.operationId != request.operationId) {
                    return@withLock ResumeDecision.Ignore("no_system_interruption")
                }
                pending.telecomSequence = maxOf(pending.telecomSequence, request.telecomSequence)
                pending.resumeRequested = true
                return@withLock ResumeDecision.RetryOwned
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

    /**
     * [resetPresentation] is false when the interruption continues in a successor (rejoin handoff
     * or replay), so the interrupted presentation never flickers through None.
     */
    private suspend fun cancelSystemInterruptionLocked(resetPresentation: Boolean = true) {
        systemInterruptionOwner?.let { owner ->
            owner.enforcementJob?.cancel()
            owner.resumeJob?.cancel()
            owner.enforcementJob = null
            owner.resumeJob = null
            owner.finishTelemetry(result = "cancelled")
        }
        systemInterruptionOwner = null
        if (!resetPresentation) return
        val session = sessionState.value ?: return
        if (session.interruptionState.local != LocalCallInterruption.None) {
            sessionState.emit(
                session.copy(
                    interruptionState = session.interruptionState.copy(local = LocalCallInterruption.None),
                ),
            )
        }
    }

    /**
     * A recoverable media loss keeps the call (service, Telecom, audio, server state) and starts
     * the single application-owned rejoin; WAITING calls and ineligible states terminate as before.
     */
    private suspend fun handleConnectionLostLocked(
        current: CallSession,
        event: MediaCallEvent.ConnectionLost,
    ): Termination? {
        val existingOwner = rejoinOwner?.takeIf { it.callId == current.callId }
        if (existingOwner != null) {
            // The replacement room died before the owner committed it.
            existingOwner.lostAgain = true
            mediaGeneration = null
            lostMediaGeneration = event.generation
            sessionState.emit(current.copy(connectionState = CallConnectionState.Reconnecting))
            return null
        }
        val lifecycle = currentLifecycleGeneration
        val rejoinable = lifecycle != null &&
            (
                current.connectionState == CallConnectionState.Connected ||
                    current.connectionState == CallConnectionState.Reconnecting
                ) &&
            (current.timing.isRunning || current.uiSnapshot.remoteParticipantConnected)
        if (!rejoinable) {
            return beginTerminationLocked(current.callId, CallConnectionState.Disconnected)
        }

        val interruption = systemInterruptionOwner?.takeIf {
            it.callId == current.callId && it.lifecycleGeneration == lifecycle && !it.completed
        }
        val pending = interruption?.let {
            PendingInterruption(
                operationId = it.operationId,
                telecomSequence = it.latestTelecomSequence,
                enableMicrophoneOnResume = it.enableMicrophoneOnResume,
                resumeRequested = current.interruptionState.local is LocalCallInterruption.Resuming,
            )
        }
        val microphoneEnabled = pending?.enableMicrophoneOnResume
            ?: current.localAudioState.shouldEnableMicrophoneOnResume()
        // The interrupted presentation stays continuous through the rejoin; the owner replays it.
        cancelSystemInterruptionLocked(resetPresentation = false)

        mediaGeneration = null
        lostMediaGeneration = event.generation
        val owner = RejoinOwner(
            id = ++operationId,
            callId = current.callId,
            lifecycleGeneration = requireNotNull(lifecycle),
            microphoneEnabled = microphoneEnabled,
            pendingInterruption = pending,
        )
        rejoinOwner = owner
        logStage(current.callId, owner.lifecycleGeneration, "rejoin", "begin")
        val latest = sessionState.value ?: current
        sessionState.emit(latest.copy(connectionState = CallConnectionState.Reconnecting))
        owner.job = repositoryScope.launch { runRejoin(owner) }
        return null
    }

    private fun RejoinOwner.isCurrentLocked(): Boolean {
        val session = sessionState.value ?: return false
        return rejoinOwner === this &&
            !terminationSignal.isRequested &&
            session.callId == callId &&
            currentLifecycleGeneration == lifecycleGeneration &&
            session.connectionState != CallConnectionState.Terminating &&
            !session.connectionState.isTerminal &&
            !disconnectOperations.containsKey(callId)
    }

    private suspend fun runRejoin(owner: RejoinOwner) {
        val startedAt = System.nanoTime()
        val networkJob = repositoryScope.launch {
            networkAvailability.available.collect { owner.kick.trySend(Unit) }
        }
        val outcome = try {
            withTimeoutOrNull(REJOIN_WINDOW_MILLIS) { rejoinLoop(owner) } ?: RejoinOutcome.Exhausted
        } finally {
            networkJob.cancel()
        }
        when (outcome) {
            RejoinOutcome.Succeeded -> logStage(owner.callId, owner.lifecycleGeneration, "rejoin", "end", startedAt)
            RejoinOutcome.Aborted -> logStage(owner.callId, owner.lifecycleGeneration, "rejoin", "aborted", startedAt)
            RejoinOutcome.Exhausted,
            RejoinOutcome.Fatal,
            -> {
                logStage(
                    owner.callId,
                    owner.lifecycleGeneration,
                    "rejoin",
                    if (outcome == RejoinOutcome.Fatal) "fatal" else "exhausted",
                    startedAt,
                )
                val termination = operationMutex.withLock {
                    if (!owner.isCurrentLocked()) return@withLock null
                    beginTerminationLocked(
                        owner.callId,
                        CallConnectionState.Failed("Voice connection could not be restored"),
                    )
                }
                termination?.let { completeTermination(owner.callId, it) }
            }
        }
    }

    private suspend fun rejoinLoop(owner: RejoinOwner): RejoinOutcome {
        var failures = 0
        while (true) {
            val attempt = attemptRejoin(owner)
            when (attempt) {
                RejoinOutcome.Succeeded,
                RejoinOutcome.Aborted,
                RejoinOutcome.Fatal,
                -> return attempt
                RejoinOutcome.Exhausted -> Unit
            }
            val backoff = (REJOIN_BACKOFF_BASE_MILLIS shl minOf(failures, REJOIN_BACKOFF_MAX_SHIFT))
                .coerceAtMost(REJOIN_BACKOFF_MAX_MILLIS)
            val jittered = (backoff * rejoinRandom.nextDouble(1.0 - REJOIN_JITTER, 1.0 + REJOIN_JITTER)).toLong()
            failures++
            // A network-available signal or termination ends the wait early.
            withTimeoutOrNull(jittered) { owner.kick.receive() }
        }
    }

    /** Exhausted here means "this attempt failed, retry if the window allows". */
    private suspend fun attemptRejoin(owner: RejoinOwner): RejoinOutcome {
        val attemptStartedAt = System.nanoTime()
        try {
            val snapshot = operationMutex.withLock {
                if (!owner.isCurrentLocked()) return@withLock null
                sessionState.value
            } ?: return RejoinOutcome.Aborted

            // Bounded token fetch; the whole attempt is additionally clipped to the remaining
            // rejoin window by the enclosing window timeout.
            val prepared = withTimeout(REJOIN_TOKEN_TIMEOUT_MILLIS) {
                sessionPreparationCoordinator.prepare(
                    CallPreparationRequest.Existing(
                        pairId = snapshot.pairId,
                        callId = snapshot.callId,
                        direction = snapshot.direction,
                        recordingConsent = recordingConsent,
                        remoteDisplayName = snapshot.remoteDisplayName,
                    ),
                )
            }
            val command = operationMutex.withLock {
                if (!owner.isCurrentLocked()) return@withLock null
                mediaConnection = prepared.mediaConnection
                MediaCallCommand.Rejoin(
                    callId = owner.callId,
                    connection = prepared.mediaConnection,
                    microphoneEnabled = owner.microphoneEnabled,
                    suspendedOperationId = owner.pendingInterruption?.operationId,
                    terminationSignal = owner.terminationSignal,
                )
            } ?: return RejoinOutcome.Aborted

            withTimeout(REJOIN_LIVEKIT_TIMEOUT_MILLIS) { callRuntimeController.execute(command) }

            // The provider emits Connected last; wait for the collector to commit it.
            val committed = withTimeoutOrNull(REJOIN_CONNECTED_WAIT_MILLIS) {
                sessionState.first { session ->
                    session?.callId != owner.callId ||
                        session.connectionState == CallConnectionState.Connected
                }
            }
            if (committed == null) {
                logStage(owner.callId, owner.lifecycleGeneration, "rejoin.attempt", "connected_missing", attemptStartedAt)
                return RejoinOutcome.Exhausted
            }

            val pending = operationMutex.withLock {
                if (!owner.isCurrentLocked()) return@withLock RejoinCommit.Aborted
                if (owner.lostAgain || mediaGeneration == null) {
                    owner.lostAgain = false
                    return@withLock RejoinCommit.Retry
                }
                rejoinOwner = null
                RejoinCommit.Done(owner.pendingInterruption)
            }
            when (pending) {
                RejoinCommit.Aborted -> return RejoinOutcome.Aborted
                RejoinCommit.Retry -> return RejoinOutcome.Exhausted
                is RejoinCommit.Done -> {
                    pending.interruption?.let { replayInterruption(owner.callId, it) }
                    return RejoinOutcome.Succeeded
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                // An expired rejoin window (outer timeout) must propagate; only termination and
                // this attempt's own timeout are handled here.
                currentCoroutineContext().ensureActive()
                if (throwable is CallTerminationException) return RejoinOutcome.Aborted
            }
            logStage(owner.callId, owner.lifecycleGeneration, "rejoin.attempt", "error", attemptStartedAt, throwable)
            if (owner.terminationSignal.isRequested) return RejoinOutcome.Aborted
            return if (throwable.isTerminalRejoinFailure()) RejoinOutcome.Fatal else RejoinOutcome.Exhausted
        }
    }

    private suspend fun replayInterruption(callId: String, pending: PendingInterruption) {
        val request = SystemCallInterruptionRequest(
            callId = callId,
            operationId = pending.operationId,
            telecomSequence = pending.telecomSequence,
        )
        suspendForSystemCall(request, microphoneOverride = pending.enableMicrophoneOnResume)
        if (pending.resumeRequested) resumeAfterSystemCall(request)
    }

    /**
     * Only authorization failures that survived the shared auth refresh, a missing/ended/conflicting
     * call, and local "already ended"/identity checks end the rejoin. Timeouts (408), throttling
     * (429), 5xx and IO errors retry within the window.
     */
    private fun Throwable.isTerminalRejoinFailure(): Boolean = when (httpStatusCode()) {
        401, 403, 404, 409, 410 -> true
        // CancellationException (e.g. an attempt timeout) is an IllegalStateException too.
        null -> this is IllegalStateException && this !is CancellationException
        else -> false
    }

    override suspend fun disconnectCall(callId: String): AppResult<Unit> = terminateCall(
        callId = callId,
        terminalState = CallConnectionState.Disconnected,
    )

    private suspend fun terminateCall(
        callId: String,
        terminalState: CallConnectionState,
    ): AppResult<Unit> = repositoryScope.async {
        // Stopping status synchronization can cancel the requesting coroutine.
        // Own the complete handoff, including starting the lazy cleanup job,
        // independently of the observer, screen, or platform callback's lifetime.
        val termination = operationMutex.withLock { beginTerminationLocked(callId, terminalState) }
        completeTermination(callId, termination)
    }.await()

    /**
     * Single entry point of the termination state machine. It closes the session to further
     * media/interruption/status mutations and registers a call-scoped teardown owner, all
     * under [operationMutex], so no other operation can replace the session in between.
     */
    private suspend fun beginTerminationLocked(
        callId: String,
        terminalState: CallConnectionState,
    ): Termination {
        disconnectOperations[callId]?.let { return Termination(it.generation, it.deferred) }

        rejoinOwner?.takeIf { it.callId == callId }?.let { owner ->
            owner.terminationSignal.request()
            owner.kick.trySend(Unit)
            rejoinOwner = null
        }
        lostMediaGeneration = null

        val session = sessionState.value
        if (session == null || session.callId != callId || session.connectionState.isTerminal) {
            if (session == null) {
                prepareOperation?.deferred?.cancel(CancellationException("Call termination requested"))
            }
            return Termination(serverEndGeneration = ++lifecycleGeneration, teardown = null)
        }

        val pendingConnect = connectOperation?.takeIf { it.callId == callId }
        pendingConnect?.terminationSignal?.request()
        val generation = pendingConnect?.generation ?: ++lifecycleGeneration
        logStage(callId, generation, "termination.request", "begin")

        mediaConnection = null
        mediaGeneration = null
        currentLifecycleGeneration = null
        cancelSystemInterruptionLocked()
        callStatusSynchronizer.stop()
        val current = requireNotNull(sessionState.value)
        if (current.connectionState != CallConnectionState.Terminating) {
            sessionState.emit(
                current.copy(
                    connectionState = CallConnectionState.Terminating,
                    timing = current.timing.stoppedLocally(System.nanoTime() / NANOS_PER_MILLI),
                    localAudioState = LocalAudioState.Disabled,
                    uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                ),
            )
        }

        // Do not await the connect owner. The termination signal has been requested; local
        // teardown owns the runtime and stale connect completions are filtered by callId.
        val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
            disconnectSession(
                callId = callId,
                requestedTerminalState = terminalState,
                generation = generation,
            )
        }
        disconnectOperations[callId] = DisconnectOperation(generation, deferred)
        disconnectingCallIdState.value = callId
        return Termination(serverEndGeneration = generation, teardown = deferred)
    }

    private suspend fun completeTermination(
        callId: String,
        termination: Termination,
    ): AppResult<Unit> {
        // Server reconciliation is deliberately independent from local teardown. Start it
        // before any synchronous/native disconnect stage can suspend or time out, and also
        // start it for idempotent/stale calls that no longer have a local session.
        enqueueServerEnd(callId, termination.serverEndGeneration)
        val teardown = termination.teardown ?: return AppResult.Success(Unit)
        teardown.start()
        return teardown.await()
    }

    /**
     * Performs local teardown. The caller is an application-scoped operation so a screen
     * being popped cannot cancel microphone/foreground-service cleanup.
     */
    private suspend fun disconnectSession(
        callId: String,
        requestedTerminalState: CallConnectionState,
        generation: Long,
    ): AppResult<Unit> {
        var failure: Throwable? = null
        var cancellation: CancellationException? = null
        val startedAt = System.nanoTime()
        logStage(callId, generation, "local.release", "begin")
        try {
            withTimeout(CALL_TERMINATION_TIMEOUT_MILLIS) {
                // The runtime releases audio, Telecom and the foreground service itself,
                // including when the media provider disconnect fails.
                callRuntimeController.execute(MediaCallCommand.Disconnect(callId))
            }
        } catch (timeout: TimeoutCancellationException) {
            failure = timeout
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            logStage(
                callId,
                generation,
                "local.release",
                if (failure == null && cancellation == null) "end" else "error",
                startedAt,
                failure ?: cancellation,
            )
            withContext(NonCancellable) {
                finalizeDisconnectSession(
                    callId = callId,
                    requestedTerminalState = requestedTerminalState,
                    generation = generation,
                    failure = failure ?: cancellation,
                )
            }
        }

        cancellation?.let { throw it }
        return failure?.let { AppResult.Failure(it.asAppError()) } ?: AppResult.Success(Unit)
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
        callId: String,
        requestedTerminalState: CallConnectionState,
        generation: Long,
        failure: Throwable?,
    ) {
        operationMutex.withLock {
            if (disconnectOperations[callId]?.generation == generation) {
                disconnectOperations.remove(callId)
            }
            val current = sessionState.value
            if (current?.callId == callId) {
                sessionState.emit(
                    current.copy(
                        connectionState = failure
                            ?.let { CallConnectionState.Failed(it.message) }
                            ?: requestedTerminalState,
                        localAudioState = LocalAudioState.Disabled,
                        uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                    ),
                )
                logStage(callId, generation, "terminal.emit", "end")
            }
            if (disconnectingCallIdState.value == callId) {
                disconnectingCallIdState.value = null
            }
        }
    }

    override suspend fun setMuted(muted: Boolean): AppResult<Unit> {
        val transitionalState = if (muted) LocalAudioState.Muting else LocalAudioState.Unmuting
        val targetState = if (muted) LocalAudioState.Muted else LocalAudioState.Enabled
        var previousAudioState: LocalAudioState = LocalAudioState.Disabled
        val callId = operationMutex.withLock {
            val session = sessionState.value
                ?: return AppResult.Failure(AppError.Validation("No call session"))
            if (session.connectionState != CallConnectionState.Connected) {
                return AppResult.Failure(AppError.Validation("Call media is not connected"))
            }
            if (session.interruptionState.local != LocalCallInterruption.None) {
                return AppResult.Failure(AppError.Validation("Call media is suspended by a system call"))
            }
            if (session.localAudioState == targetState) return AppResult.Success(Unit)
            if (!session.localAudioState.canToggleMute) {
                return AppResult.Failure(AppError.Validation("Microphone state is not ready"))
            }
            previousAudioState = session.localAudioState
            sessionState.emit(session.copy(localAudioState = transitionalState))
            session.callId
        }

        // The runtime call stays outside operationMutex: a stuck provider must never block
        // hang-up, media events or status synchronization.
        var result: AppResult<Unit>? = null
        try {
            result = runCatchingAppResult {
                callRuntimeController.execute(MediaCallCommand.SetMuted(callId = callId, muted = muted))
            }
        } finally {
            withContext(NonCancellable) {
                operationMutex.withLock {
                    val current = sessionState.value
                    // Termination or a provider audio fact may have superseded this transition.
                    if (current?.callId != callId || current.localAudioState != transitionalState) {
                        return@withLock
                    }
                    val committedState = if (result is AppResult.Success) targetState else previousAudioState
                    sessionState.emit(current.copy(localAudioState = committedState))
                }
            }
        }
        return requireNotNull(result)
    }

    override suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit> {
        operationMutex.withLock {
            val session = sessionState.value
                ?: return AppResult.Failure(AppError.Validation("No call session"))
            if (session.connectionState != CallConnectionState.Connected) {
                return AppResult.Failure(AppError.Validation("Call media is not connected"))
            }
            if (session.interruptionState.local != LocalCallInterruption.None) {
                return AppResult.Failure(AppError.Validation("Audio route is locked during a system call"))
            }
        }
        // The runtime ignores route requests once its call has been released.
        return runCatchingAppResult { callRuntimeController.selectAudioRoute(route) }
    }

    private fun startCallStatusSync(callId: String) {
        callStatusSynchronizer.start(callId) { callStatus ->
            val termination = operationMutex.withLock {
                val latestSession = sessionState.value
                if (latestSession == null || latestSession.callId != callId) return@withLock null
                if (latestSession.connectionState.isTerminal ||
                    latestSession.connectionState == CallConnectionState.Terminating
                ) return@withLock null
                val syncedSession = latestSession.copy(
                    recordingState = callStatus.recordingStatus.toRecordingState(),
                    timing = callStatus.toCallTiming(previous = latestSession.timing),
                )
                if (syncedSession != latestSession) {
                    sessionState.emit(syncedSession)
                }
                if (!callStatus.state.equals("ended", ignoreCase = true)) return@withLock null
                // Route status-driven termination through the same call-scoped owner as UI,
                // notification, Telecom, and media events.
                beginTerminationLocked(callId, CallConnectionState.Disconnected)
            } ?: return@start true

            // Registering the termination stopped this observation, so the remaining
            // handoff must not run in the observer's (now cancelled) coroutine.
            repositoryScope.launch { completeTermination(callId, termination) }
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

    private suspend inline fun <T> runCatchingAppResult(block: suspend () -> T): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppResult.Failure(throwable.asAppError())
        }
    }

    private companion object {
        const val LOG_TAG = "CallLifecycle"
        const val NANOS_PER_MILLI = 1_000_000L
        /** Outer safety deadline; individual stages carry their own budgets in the runtime. */
        const val CONNECT_TIMEOUT_MILLIS = 40_000L
        const val REJOIN_WINDOW_MILLIS = 45_000L
        const val REJOIN_TOKEN_TIMEOUT_MILLIS = 10_000L
        /** Matches the runtime LiveKit stage cap. */
        const val REJOIN_LIVEKIT_TIMEOUT_MILLIS = 25_000L
        const val REJOIN_CONNECTED_WAIT_MILLIS = 5_000L
        const val REJOIN_BACKOFF_BASE_MILLIS = 1_000L
        const val REJOIN_BACKOFF_MAX_MILLIS = 8_000L
        const val REJOIN_BACKOFF_MAX_SHIFT = 3
        const val REJOIN_JITTER = 0.2
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
    data object Deferred : SuspendDecision
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

/** Mutable, identity-compared owner of one system-call interruption operation. */
private class SystemInterruptionOwner(
    val callId: String,
    val lifecycleGeneration: Long,
    val mediaGeneration: Long,
    val operationId: String,
    var latestTelecomSequence: Long,
    val enableMicrophoneOnResume: Boolean,
) {
    var enforcementJob: Job? = null
    var resumeJob: Job? = null
    var completed: Boolean = false
    var telemetryOperation: CallInterruptionTelemetryOperation? = null
}

/** Application-owned, per-call rejoin of the media room after a recoverable transport loss. */
private class RejoinOwner(
    val id: Long,
    val callId: String,
    val lifecycleGeneration: Long,
    val microphoneEnabled: Boolean,
    var pendingInterruption: PendingInterruption?,
) {
    val terminationSignal = CallTerminationSignal()
    val kick = Channel<Unit>(Channel.CONFLATED)
    var job: Job? = null
    var lostAgain: Boolean = false
}

private class PendingInterruption(
    val operationId: String,
    var telecomSequence: Long,
    val enableMicrophoneOnResume: Boolean,
    var resumeRequested: Boolean,
)

private enum class RejoinOutcome { Succeeded, Aborted, Fatal, Exhausted }

private sealed interface RejoinCommit {
    data object Aborted : RejoinCommit
    data object Retry : RejoinCommit
    data class Done(val interruption: PendingInterruption?) : RejoinCommit
}

private class DisconnectOperation(
    val generation: Long,
    val deferred: Deferred<AppResult<Unit>>,
)

/** Result of registering a termination: a server end is always due, local teardown only for a live session. */
private class Termination(
    val serverEndGeneration: Long,
    val teardown: Deferred<AppResult<Unit>>?,
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
