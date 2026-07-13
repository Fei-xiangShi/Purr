package life.fxs.purr.data.account.realtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.ActiveCallDto
import life.fxs.purr.core.network.model.RealtimeEventDto
import life.fxs.purr.data.account.network.SessionTokenHolder
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.repository.RealtimeRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val SNAPSHOT_EVENT = "snapshot"
private const val PRESENCE_EVENT = "presence_changed"
private const val CALL_STARTED_EVENT = "call_started"
private const val CALL_ENDED_EVENT = "call_ended"

@Singleton
class ApiRealtimeRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val sessionTokenHolder: SessionTokenHolder,
    private val api: PurrCallApi,
    @RealtimeEndpoint private val realtimeUrl: String,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : RealtimeRepository {
    private val scope = applicationScope
    private val state = MutableStateFlow(RealtimeState())
    private var shouldRun = false
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0

    override fun observeState(): Flow<RealtimeState> = state.asStateFlow()

    @Synchronized
    override fun start() {
        if (shouldRun) return
        shouldRun = true
        openSocket()
    }

    @Synchronized
    override fun stop() {
        shouldRun = false
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close(NORMAL_CLOSURE_STATUS, "client stopped")
        socket = null
        reconnectAttempt = 0
        state.value = RealtimeState()
    }

    override suspend fun refreshActiveCall(): AppResult<Unit> = appResult {
        val activeCall = api.getActiveCall().activeCall
        state.update { current ->
            current.copy(incomingCall = activeCall.toIncomingCallOrNull())
        }
    }

    override suspend fun declineIncomingCall(callId: String): AppResult<Unit> = appResult {
        api.endCall(callId)
        clearIncomingCall(callId)
    }

    override fun clearIncomingCall(callId: String) {
        state.update { current ->
            if (current.incomingCall?.callId == callId) current.copy(incomingCall = null) else current
        }
    }

    @Synchronized
    private fun openSocket() {
        if (!shouldRun || socket != null) return
        val accessToken = sessionTokenHolder.accessToken()
        if (accessToken.isNullOrBlank()) {
            scheduleReconnect()
            return
        }
        val request = Request.Builder()
            .url(realtimeUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()
        socket = okHttpClient.newWebSocket(request, Listener())
    }

    @Synchronized
    private fun handleOpen(webSocket: WebSocket) {
        if (!shouldRun) {
            webSocket.close(NORMAL_CLOSURE_STATUS, "connection no longer needed")
            return
        }
        if (socket !== webSocket) {
            webSocket.close(NORMAL_CLOSURE_STATUS, "stale connection")
            return
        }
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        state.update { it.copy(isConnected = true) }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                webSocket.send(HEARTBEAT_MESSAGE)
            }
        }
    }

    @Synchronized
    private fun handleMessage(webSocket: WebSocket, text: String) {
        if (socket !== webSocket) return
        val event = runCatching { json.decodeFromString<RealtimeEventDto>(text) }.getOrNull() ?: return
        state.update { current ->
            current.applyRealtimeEvent(event, sessionTokenHolder.userId())
        }
    }

    @Synchronized
    private fun handleClosed(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        state.update { it.copy(isConnected = false, partnerOnline = null) }
        scheduleReconnect()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!shouldRun || reconnectJob?.isActive == true) return
        val delayMillis = minOf(
            MAX_RECONNECT_DELAY_MILLIS,
            INITIAL_RECONNECT_DELAY_MILLIS * (1L shl minOf(reconnectAttempt, MAX_RECONNECT_SHIFT)),
        )
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMillis)
            synchronized(this@ApiRealtimeRepository) {
                reconnectJob = null
                openSocket()
            }
        }
    }

    private suspend fun appResult(block: suspend () -> Unit): AppResult<Unit> = try {
        block()
        AppResult.Success(Unit)
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AppResult.Failure(throwable.asAppError())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = handleOpen(webSocket)

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, text)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClosed(webSocket)

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) = handleClosed(webSocket)
    }

    private companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
        const val HEARTBEAT_MESSAGE = "heartbeat"
        const val HEARTBEAT_INTERVAL_MILLIS = 15_000L
        const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
        const val MAX_RECONNECT_SHIFT = 5
    }
}

internal fun RealtimeState.applyRealtimeEvent(
    event: RealtimeEventDto,
    currentUserId: String?,
): RealtimeState = when (event.type) {
    SNAPSHOT_EVENT -> copy(
        partnerOnline = event.partnerOnline,
        incomingCall = event.toIncomingCallOrNull(currentUserId),
    )
    PRESENCE_EVENT -> copy(partnerOnline = event.partnerOnline)
    CALL_STARTED_EVENT -> copy(incomingCall = event.toIncomingCallOrNull(currentUserId))
    CALL_ENDED_EVENT -> if (incomingCall?.callId == event.callId) {
        copy(incomingCall = null)
    } else {
        this
    }
    else -> this
}

internal fun RealtimeEventDto.toIncomingCallOrNull(currentUserId: String?): IncomingCall? {
    val eventCallId = callId ?: return null
    val eventPairId = pairId ?: return null
    val eventCallerUserId = callerUserId ?: return null
    val eventStartedAt = startedAtEpochMillis ?: return null
    if (eventCallerUserId == currentUserId) return null
    return IncomingCall(eventCallId, eventPairId, eventCallerUserId, eventStartedAt)
}

internal fun ActiveCallDto?.toIncomingCallOrNull(): IncomingCall? {
    val activeCall = this?.takeIf { it.isIncoming } ?: return null
    return IncomingCall(
        callId = activeCall.callId,
        pairId = activeCall.pairId,
        callerUserId = activeCall.callerUserId,
        startedAtEpochMillis = activeCall.startedAtEpochMillis,
    )
}
