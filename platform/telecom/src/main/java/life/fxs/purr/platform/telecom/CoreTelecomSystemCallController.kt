package life.fxs.purr.platform.telecom

import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection

@Singleton
class CoreTelecomSystemCallController @Inject internal constructor(
    private val callGateway: TelecomCallGateway,
    private val attributesFactory: TelecomCallAttributesFactory,
    @ApplicationScope private val applicationScope: CoroutineScope,
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
        } catch (cancellation: CancellationException) {
            cancelPendingSession(pending)
            throw cancellation
        }
    }

    override suspend fun activateCall(callId: String) {
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
    }

    override suspend fun disconnectCall(callId: String) {
        val session = synchronized(lock) {
            activeSession?.takeIf { it.descriptor.callId == callId }
        } ?: return
        session.disconnectMutex.withLock {
            if (session.closed.isCompleted || session.signals.telecomDisconnectInProgress) return@withLock
            session.signals.localDisconnectRequested = true
            try {
                session.controlScope.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    .requireSuccess("disconnect")
                withTimeout(CALL_CLOSE_TIMEOUT_MILLIS) { session.closed.await() }
            } catch (timeout: TimeoutCancellationException) {
                session.owner.job?.cancel(timeout)
                throw timeout
            } catch (error: Throwable) {
                if (!session.closed.isCompleted) {
                    session.signals.localDisconnectRequested = false
                }
                throw error
            }
        }
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
                    onSetActive = {},
                    onSetInactive = {
                        emitDisconnectRequest(
                            callId = pending.descriptor.callId,
                            telecomDisconnectInProgress = false,
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
            val shouldEmitUnexpectedDisconnect = synchronized(lock) {
                val ended = activeSession?.takeIf { it.owner === pending }
                val unexpected = ended != null && !ended.signals.hasExpectedClosure
                if (unexpected) {
                    ended.signals.telecomDisconnectInProgress = true
                    ended.signals.disconnectEventEmitted = true
                }
                unexpected
            }
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
                    synchronized(session.routeStateLock) {
                        session.currentRoute = route
                        if (session.requestedRoute == route) session.requestedRoute = null
                    }
                    mutableActiveRoute.value = route
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

    private suspend fun emitDisconnectRequest(
        callId: String,
        telecomDisconnectInProgress: Boolean,
    ) {
        val shouldEmit = synchronized(lock) {
            val signals = activeSession
                ?.takeIf { it.descriptor.callId == callId }
                ?.signals
                ?: pendingSession
                    ?.takeIf { it.descriptor.callId == callId }
                    ?.signals
                ?: return@synchronized false
            if (telecomDisconnectInProgress) signals.telecomDisconnectInProgress = true
            if (signals.disconnectEventEmitted) {
                false
            } else {
                signals.disconnectEventEmitted = true
                true
            }
        }
        if (shouldEmit) mutableEvents.emit(SystemCallEvent.DisconnectRequested(callId))
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
        if (shouldEmit) mutableEvents.emit(SystemCallEvent.AnswerRequested(callId))
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
        val routeRequestMutex = Mutex()
        val routeStateLock = Any()

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

        val hasExpectedClosure: Boolean
            get() = telecomDisconnectInProgress || localDisconnectRequested || disconnectEventEmitted
    }

    private companion object {
        val DEFAULT_ROUTES = listOf(AudioRoute.Earpiece, AudioRoute.Speaker)
        const val CALL_CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

private fun CallControlResult.requireSuccess(operation: String) {
    if (this is CallControlResult.Error) {
        error("Telecom could not $operation call: errorCode=$errorCode")
    }
}
