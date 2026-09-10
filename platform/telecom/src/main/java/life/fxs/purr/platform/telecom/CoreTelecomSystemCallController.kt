package life.fxs.purr.platform.telecom

import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.media.telecom.SystemCallInterruptionDispatcher
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetry
import life.fxs.purr.core.media.telemetry.NoOpCallInterruptionTelemetry
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult

@Singleton
class CoreTelecomSystemCallController @Inject internal constructor(
    private val callGateway: TelecomCallGateway,
    private val attributesFactory: TelecomCallAttributesFactory,
    private val interruptionDispatcher: SystemCallInterruptionDispatcher,
    private val logger: PurrLogger,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val interruptionTelemetry: CallInterruptionTelemetry = NoOpCallInterruptionTelemetry,
) : SystemCallController, AudioRouteController {
    private val lock = Any()
    private val mutableEvents = MutableSharedFlow<SystemCallEvent>(extraBufferCapacity = 8)
    private val mutableAvailableRoutes = MutableStateFlow(DEFAULT_ROUTES)
    private val mutableActiveRoute = MutableStateFlow(AudioRoute.Earpiece)
    private var pendingSession: PendingSession? = null
    private var activeSession: ActiveSession? = null

    override val events: Flow<SystemCallEvent> = mutableEvents.asSharedFlow()
    override val availableRoutes: StateFlow<List<AudioRoute>> = mutableAvailableRoutes.asStateFlow()
    override val activeRoute: StateFlow<AudioRoute> = mutableActiveRoute.asStateFlow()

    override suspend fun startCall(descriptor: SystemCallDescriptor) {
        check(interruptionDispatcher.isRegistered) {
            "System-call interruption handling must be initialized before starting a Telecom call"
        }
        logger.d(LOG_TAG, "callId=${descriptor.callId} phase=telecom.start event=begin direction=${descriptor.direction}")
        val pending = synchronized(lock) {
            activeSession?.let { current ->
                return
            }
            pendingSession?.let { current ->
                return@synchronized current
            }

            callGateway.registerApp()
            PendingSession(descriptor, CompletableDeferred()).also { created ->
                pendingSession = created
                created.job = applicationScope.launch { runCallSession(created) }
            }
        }
        try {
            pending.ready.await()
            logger.d(LOG_TAG, "callId=${descriptor.callId} phase=telecom.start event=ready")
        } catch (cancellation: CancellationException) {
            cancelPendingSession(pending)
            throw cancellation
        }
    }

    override suspend fun activateCall(callId: String) {
        logger.d(LOG_TAG, "callId=$callId phase=telecom.activate event=begin")
        val session = requireActiveSession(callId)
        val result = when (session.descriptor.direction) {
            CallDirection.Incoming -> if (session.signals.telecomAnswerRequested) {
                null
            } else {
                session.controlScope.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
            }
            CallDirection.Outgoing -> session.controlScope.setActive()
        }
        result?.requireSuccess("activate")
        logger.d(LOG_TAG, "callId=$callId phase=telecom.activate event=end")
    }

    override suspend fun disconnectCall(callId: String) {
        val startedAt = System.nanoTime()
        logger.d(LOG_TAG, "callId=$callId phase=telecom.disconnect event=begin")
        val session = synchronized(lock) {
            activeSession?.takeIf { it.descriptor.callId == callId }
        } ?: run {
            logger.d(LOG_TAG, "callId=$callId phase=telecom.disconnect event=no_active_session")
            return
        }
        session.disconnectMutex.withLock {
            if (session.closed.isCompleted || session.signals.telecomDisconnectInProgress) return@withLock
            session.signals.localDisconnectRequested = true
            markInterruptionTerminal(session)
            try {
                session.controlScope.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    .requireSuccess("disconnect")
                // Telecom may take several seconds to deliver the final close
                // callback. The app's local call state must not wait for that
                // callback, otherwise a hang-up appears stuck and the next call
                // is incorrectly blocked by an old platform session.
                detachActiveSession(session)
                applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        withTimeout(CALL_CLOSE_TIMEOUT_MILLIS) { session.closed.await() }
                    } catch (timeout: TimeoutCancellationException) {
                        session.owner.job?.cancel(timeout)
                    }
                }
                logger.d(
                    LOG_TAG,
                    "callId=$callId phase=telecom.disconnect event=detached elapsedMs=${elapsedMillis(startedAt)}",
                )
            } catch (timeout: TimeoutCancellationException) {
                throw timeout
            } catch (error: Throwable) {
                if (!session.closed.isCompleted) {
                    session.signals.localDisconnectRequested = false
                }
                detachActiveSession(session)
                logger.e(
                    LOG_TAG,
                    error,
                    "callId=$callId phase=telecom.disconnect event=error elapsedMs=${elapsedMillis(startedAt)}",
                )
                throw error
            }
        }
    }

    private fun detachActiveSession(session: ActiveSession) {
        synchronized(lock) {
            if (activeSession === session) activeSession = null
        }
        mutableAvailableRoutes.value = DEFAULT_ROUTES
        mutableActiveRoute.value = AudioRoute.Earpiece
    }

    override suspend fun selectRoute(route: AudioRoute) {
        val session = requireNotNull(synchronized(lock) { activeSession }) {
            "No system call is active"
        }
        val endpoint = session.endpointsByRoute[route]
            ?: error("Audio route $route is unavailable")
        session.routeRequestMutex.withLock {
            requestEndpointIfNeeded(session, route, endpoint, "change endpoint")
            session.defaultRouteApplied = true
        }
    }

    override suspend fun selectDefaultRoute() {
        val session = synchronized(lock) { activeSession } ?: return
        applyDefaultRoute(session)
    }

    override suspend fun releaseCallRoute() = Unit

    private suspend fun runCallSession(pending: PendingSession) {
        try {
            callGateway.addCall(
                attributes = attributesFactory.create(pending.descriptor),
                callbacks = TelecomCallCallbacks(
                    onAnswer = {
                        emitAnswerRequest(pending.descriptor.callId)
                    },
                    onDisconnect = {
                        emitDisconnectRequest(
                            callId = pending.descriptor.callId,
                            telecomDisconnectInProgress = true,
                        )
                    },
                    onSetActive = {
                        handleInterruptionCallback(
                            callId = pending.descriptor.callId,
                            callback = InterruptionCallback.Active,
                        )
                    },
                    onSetInactive = {
                        handleInterruptionCallback(
                            callId = pending.descriptor.callId,
                            callback = InterruptionCallback.Inactive,
                        )
                    },
                ),
            ) { controlScope ->
                val session = ActiveSession(
                    descriptor = pending.descriptor,
                    controlScope = controlScope,
                    closed = pending.closed,
                    signals = pending.signals,
                    owner = pending,
                )
                synchronized(lock) {
                    activeSession = session
                    pendingSession = null
                }
                controlScope.observeEndpoints(session)
                pending.ready.complete(Unit)
            }
        } catch (cancellation: CancellationException) {
            pending.ready.cancel(cancellation)
            throw cancellation
        } catch (error: Throwable) {
            pending.ready.completeExceptionally(error)
        } finally {
            var shouldEmitUnexpectedDisconnect = false
            val endedSession = synchronized(lock) {
                val ended = activeSession?.takeIf { it.owner === pending }
                val unexpected = ended != null && !ended.signals.hasExpectedClosure
                shouldEmitUnexpectedDisconnect = unexpected
                if (unexpected) {
                    ended.signals.telecomDisconnectInProgress = true
                    ended.signals.disconnectEventEmitted = true
                }
                ended
            }
            endedSession?.let(::markInterruptionTerminal)
            pending.closed.complete(Unit)
            synchronized(lock) {
                if (pendingSession === pending) pendingSession = null
                if (activeSession?.owner === pending) activeSession = null
            }
            mutableAvailableRoutes.value = DEFAULT_ROUTES
            mutableActiveRoute.value = AudioRoute.Earpiece
            if (shouldEmitUnexpectedDisconnect) {
                withContext(NonCancellable) {
                    mutableEvents.emit(SystemCallEvent.DisconnectRequested(pending.descriptor.callId))
                }
            }
        }
    }

    private fun CallControlScope.observeEndpoints(session: ActiveSession) {
        launch {
            currentCallEndpoint.collect { endpoint ->
                endpoint.toAudioRoute()?.let { route ->
                    val shouldPublish = synchronized(session.routeStateLock) {
                        session.currentRoute = route
                        when (session.requestedRoute) {
                            route -> {
                                session.requestedRoute = null
                                true
                            }
                            null -> true
                            else -> false
                        }
                    }
                    if (shouldPublish) mutableActiveRoute.value = route
                }
            }
        }
        launch {
            availableEndpoints.collect { endpoints ->
                val mapped = endpoints.mapNotNull { endpoint ->
                    endpoint.toAudioRoute()?.let { route -> route to endpoint }
                }.toMap()
                session.endpointsByRoute = mapped
                mutableAvailableRoutes.value = mapped.keys.toList().ifEmpty { DEFAULT_ROUTES }
                applyDefaultRoute(session)
            }
        }
    }

    private suspend fun applyDefaultRoute(session: ActiveSession) {
        session.routeRequestMutex.withLock {
            if (session.defaultRouteApplied) return@withLock
            val endpoint = session.endpointsByRoute[AudioRoute.Earpiece] ?: return@withLock
            requestEndpointIfNeeded(session, AudioRoute.Earpiece, endpoint, "select default endpoint")
            session.defaultRouteApplied = true
        }
    }

    private suspend fun requestEndpointIfNeeded(
        session: ActiveSession,
        route: AudioRoute,
        endpoint: CallEndpointCompat,
        operation: String,
    ) {
        val shouldRequest = synchronized(session.routeStateLock) {
            when {
                session.requestedRoute == route -> false
                session.requestedRoute == null && session.currentRoute == route -> false
                else -> {
                    session.requestedRoute = route
                    true
                }
            }
        }
        if (!shouldRequest) return
        try {
            session.controlScope.requestEndpointChange(endpoint).requireSuccess(operation)
            // A successful request means Telecom accepted the target endpoint.
            // Publish it immediately so UI state does not depend on an OEM
            // delivering a follow-up currentCallEndpoint emission. The pending
            // route remains recorded until Telecom confirms it, allowing stale
            // callbacks from an older request to be ignored above.
            mutableActiveRoute.value = route
        } catch (error: Throwable) {
            synchronized(session.routeStateLock) {
                if (session.requestedRoute == route) session.requestedRoute = null
            }
            throw error
        }
    }

    private fun requireActiveSession(callId: String): ActiveSession = synchronized(lock) {
        requireNotNull(activeSession?.takeIf { it.descriptor.callId == callId }) {
            "System call $callId is not active"
        }
    }

    private suspend fun handleInterruptionCallback(
        callId: String,
        callback: InterruptionCallback,
    ) {
        val callbackEnteredAt = System.nanoTime()
        val session = synchronized(lock) {
            activeSession?.takeIf { it.descriptor.callId == callId }
        }
        if (session == null) {
            logger.d(
                LOG_TAG,
                "callId=$callId phase=telecom.interruption event=ignored reason=no_active_session callback=${callback.value}",
            )
            return
        }

        val request = synchronized(session.interruptionStateLock) {
            if (session.signals.interruptionTerminal) return@synchronized null
            val sequence = ++session.signals.interruptionSequence
            when (callback) {
                InterruptionCallback.Inactive -> {
                    if (!session.signals.systemCallInactive) {
                        session.signals.interruptionOperationId = UUID.randomUUID().toString()
                    }
                    session.signals.systemCallInactive = true
                }
                InterruptionCallback.Active -> {
                    if (session.signals.interruptionOperationId == null) return@synchronized null
                    session.signals.systemCallInactive = false
                }
            }
            SystemCallInterruptionRequest(
                callId = callId,
                operationId = requireNotNull(session.signals.interruptionOperationId),
                telecomSequence = sequence,
            )
        }
        if (request == null) {
            logger.d(
                LOG_TAG,
                "callId=$callId phase=telecom.interruption event=ignored reason=no_matching_operation callback=${callback.value}",
            )
            return
        }

        val operation = applicationScope.async {
            session.interruptionMutex.withLock {
                val canDispatch = synchronized(session.interruptionStateLock) {
                    !session.signals.interruptionTerminal
                } && synchronized(lock) { activeSession === session }
                if (!canDispatch) {
                    return@withLock SystemCallInterruptionResult.Ignored("termination_won")
                }
                when (callback) {
                    InterruptionCallback.Inactive -> interruptionDispatcher.dispatchInactive(request)
                    InterruptionCallback.Active -> interruptionDispatcher.dispatchActive(request)
                }
            }
        }
        trackInterruptionOperation(session, operation)

        val remainingMillis = TELECOM_CALLBACK_BUDGET_MILLIS - elapsedMillis(callbackEnteredAt)
        if (remainingMillis <= 0L) {
            logInterruptionCallback(
                request = request,
                callback = callback,
                result = "transferred_timeout",
                callbackEnteredAt = callbackEnteredAt,
            )
            return
        }
        try {
            val result = withTimeout(remainingMillis) { operation.await() }
            logInterruptionCallback(
                request = request,
                callback = callback,
                result = result::class.simpleName ?: "unknown",
                callbackEnteredAt = callbackEnteredAt,
            )
        } catch (_: TimeoutCancellationException) {
            logInterruptionCallback(
                request = request,
                callback = callback,
                result = "transferred_timeout",
                callbackEnteredAt = callbackEnteredAt,
            )
        } catch (cancellation: CancellationException) {
            recordInterruptionCallbackTelemetry(
                request = request,
                callback = callback,
                result = "cancelled",
                callbackEnteredAt = callbackEnteredAt,
            )
            logger.d(
                LOG_TAG,
                "callId=${request.callId} operationId=${request.operationId} sequence=${request.telecomSequence} " +
                    "phase=telecom.interruption event=callback_cancelled callback=${callback.value} " +
                    "elapsedMs=${elapsedMillis(callbackEnteredAt)}",
            )
        } catch (error: Throwable) {
            recordInterruptionCallbackTelemetry(
                request = request,
                callback = callback,
                result = "dispatch_error",
                callbackEnteredAt = callbackEnteredAt,
            )
            logger.e(
                LOG_TAG,
                error,
                "callId=${request.callId} operationId=${request.operationId} sequence=${request.telecomSequence} " +
                    "phase=telecom.interruption event=dispatch_error callback=${callback.value} " +
                    "elapsedMs=${elapsedMillis(callbackEnteredAt)}",
            )
        }
    }

    private fun trackInterruptionOperation(
        session: ActiveSession,
        operation: Deferred<SystemCallInterruptionResult>,
    ) {
        synchronized(session.interruptionStateLock) {
            if (session.signals.interruptionTerminal) {
                operation.cancel(CancellationException("Telecom session is terminal"))
            } else {
                session.interruptionOperations += operation
            }
        }
        operation.invokeOnCompletion {
            synchronized(session.interruptionStateLock) {
                session.interruptionOperations -= operation
            }
        }
    }

    private fun markInterruptionTerminal(session: ActiveSession) {
        val operations = synchronized(session.interruptionStateLock) {
            session.signals.interruptionTerminal = true
            session.interruptionOperations.toList().also { session.interruptionOperations.clear() }
        }
        operations.forEach { operation ->
            operation.cancel(CancellationException("Telecom session terminated"))
        }
    }

    private fun logInterruptionCallback(
        request: SystemCallInterruptionRequest,
        callback: InterruptionCallback,
        result: String,
        callbackEnteredAt: Long,
    ) {
        runCatching {
            recordInterruptionCallbackTelemetry(
                request = request,
                callback = callback,
                result = result,
                callbackEnteredAt = callbackEnteredAt,
            )
            logger.d(
                LOG_TAG,
                "callId=${request.callId} operationId=${request.operationId} sequence=${request.telecomSequence} " +
                    "phase=telecom.interruption event=callback_complete callback=${callback.value} result=$result " +
                    "elapsedMs=${elapsedMillis(callbackEnteredAt)}",
            )
        }
    }

    private fun recordInterruptionCallbackTelemetry(
        request: SystemCallInterruptionRequest,
        callback: InterruptionCallback,
        result: String,
        callbackEnteredAt: Long,
    ) {
        runCatching {
            interruptionTelemetry.recordTelecomCallback(
                callId = request.callId,
                operationId = request.operationId,
                sequence = request.telecomSequence,
                callback = callback.value,
                result = result,
                elapsedMillis = elapsedMillis(callbackEnteredAt),
            )
        }
    }

    private suspend fun emitDisconnectRequest(
        callId: String,
        telecomDisconnectInProgress: Boolean,
    ) {
        var activeToTerminate: ActiveSession? = null
        val shouldEmit = synchronized(lock) {
            val matchingActive = activeSession
                ?.takeIf { it.descriptor.callId == callId }
            activeToTerminate = matchingActive
            val signals = matchingActive?.signals
                ?: pendingSession
                    ?.takeIf { it.descriptor.callId == callId }
                    ?.signals
                ?: return@synchronized false
            signals.interruptionTerminal = true
            if (telecomDisconnectInProgress) signals.telecomDisconnectInProgress = true
            if (signals.disconnectEventEmitted) {
                false
            } else {
                signals.disconnectEventEmitted = true
                true
            }
        }
        activeToTerminate?.let(::markInterruptionTerminal)
        if (shouldEmit) {
            logger.d(LOG_TAG, "callId=$callId phase=telecom.callback event=disconnect_requested inProgress=$telecomDisconnectInProgress")
            mutableEvents.emit(SystemCallEvent.DisconnectRequested(callId))
        }
    }

    private suspend fun emitAnswerRequest(callId: String) {
        val shouldEmit = synchronized(lock) {
            val signals = activeSession
                ?.takeIf { it.descriptor.callId == callId }
                ?.signals
                ?: pendingSession
                    ?.takeIf { it.descriptor.callId == callId }
                    ?.signals
                ?: return@synchronized false
            signals.telecomAnswerRequested = true
            if (signals.answerEventEmitted) {
                false
            } else {
                signals.answerEventEmitted = true
                true
            }
        }
        if (shouldEmit) {
            logger.d(LOG_TAG, "callId=$callId phase=telecom.callback event=answer_requested")
            mutableEvents.emit(SystemCallEvent.AnswerRequested(callId))
        }
    }

    private fun cancelPendingSession(pending: PendingSession) {
        val shouldCancel = synchronized(lock) {
            pendingSession === pending && activeSession == null
        }
        if (shouldCancel) pending.job?.cancel()
    }

    private class PendingSession(
        val descriptor: SystemCallDescriptor,
        val ready: CompletableDeferred<Unit>,
    ) {
        val closed = CompletableDeferred<Unit>()
        val signals = SessionSignals()
        var job: Job? = null
    }

    private class ActiveSession(
        val descriptor: SystemCallDescriptor,
        val controlScope: CallControlScope,
        val closed: CompletableDeferred<Unit>,
        val signals: SessionSignals,
        val owner: PendingSession,
    ) {
        @Volatile
        var endpointsByRoute: Map<AudioRoute, CallEndpointCompat> = emptyMap()

        @Volatile
        var defaultRouteApplied: Boolean = false

        val disconnectMutex = Mutex()
        val interruptionMutex = Mutex()
        val routeRequestMutex = Mutex()
        val routeStateLock = Any()
        val interruptionStateLock = Any()
        val interruptionOperations = mutableSetOf<Job>()

        @Volatile
        var currentRoute: AudioRoute? = null

        @Volatile
        var requestedRoute: AudioRoute? = null
    }

    private class SessionSignals {
        @Volatile
        var telecomAnswerRequested: Boolean = false

        @Volatile
        var answerEventEmitted: Boolean = false

        @Volatile
        var telecomDisconnectInProgress: Boolean = false

        @Volatile
        var localDisconnectRequested: Boolean = false

        @Volatile
        var disconnectEventEmitted: Boolean = false

        @Volatile
        var interruptionTerminal: Boolean = false

        @Volatile
        var systemCallInactive: Boolean = false

        @Volatile
        var interruptionOperationId: String? = null

        @Volatile
        var interruptionSequence: Long = 0L

        val hasExpectedClosure: Boolean
            get() = telecomDisconnectInProgress || localDisconnectRequested || disconnectEventEmitted
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        const val LOG_TAG = "CallTelecom"
        val DEFAULT_ROUTES = listOf(AudioRoute.Earpiece, AudioRoute.Speaker)
        const val CALL_CLOSE_TIMEOUT_MILLIS = 5_000L
        const val TELECOM_CALLBACK_BUDGET_MILLIS = 4_000L
    }
}

private enum class InterruptionCallback(val value: String) {
    Inactive("inactive"),
    Active("active"),
}

private fun CallControlResult.requireSuccess(operation: String) {
    if (this is CallControlResult.Error) {
        error("Telecom could not $operation call: errorCode=$errorCode")
    }
}
