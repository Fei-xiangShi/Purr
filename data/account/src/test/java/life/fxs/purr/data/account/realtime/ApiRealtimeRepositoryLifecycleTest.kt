package life.fxs.purr.data.account.realtime

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.ActiveCallDto
import life.fxs.purr.core.network.model.ActiveCallResponseDto
import life.fxs.purr.data.account.network.SessionTokenHolder
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiRealtimeRepositoryLifecycleTest {
    @Test
    fun `response from a previous authenticated lifecycle cannot restore a call`() = runTest {
        val response = CompletableDeferred<ActiveCallResponseDto>()
        val api = mockk<PurrCallApi> {
            io.mockk.coEvery { getActiveCall() } coAnswers { response.await() }
        }
        val webSocket = mockk<WebSocket>(relaxed = true)
        val client = mockk<OkHttpClient> {
            every { newWebSocket(any(), any()) } returns webSocket
        }
        val tokenHolder = SessionTokenHolder().apply {
            update(accessToken = "token", refreshToken = "refresh", userId = "user-a")
        }
        val repository = ApiRealtimeRepository(
            okHttpClient = client,
            json = Json,
            sessionTokenHolder = tokenHolder,
            api = api,
            realtimeUrl = "wss://example.invalid/realtime",
            applicationScope = this,
        )

        repository.start()
        val staleRefresh = async { repository.refreshActiveCall() }
        runCurrent()
        repository.stop()
        repository.start()
        response.complete(ActiveCallResponseDto(activeCall = incomingCall()))
        staleRefresh.await()

        assertThat(repository.observeState().first().incomingCallCandidate).isNull()
    }

    @Test
    fun `slow poll and stale snapshot cannot revive an ended incoming call`() = runTest {
        val response = CompletableDeferred<ActiveCallResponseDto>()
        val api = mockk<PurrCallApi> {
            io.mockk.coEvery { getActiveCall() } coAnswers { response.await() }
        }
        val listener = slot<WebSocketListener>()
        val socket = mockk<WebSocket>(relaxed = true)
        val client = mockk<OkHttpClient> {
            every { newWebSocket(any(), capture(listener)) } returns socket
        }
        val repository = ApiRealtimeRepository(
            okHttpClient = client,
            json = Json,
            sessionTokenHolder = SessionTokenHolder().apply {
                update("token", "refresh", "user-a")
            },
            api = api,
            realtimeUrl = "wss://example.invalid/realtime",
            applicationScope = backgroundScope,
        )
        repository.start()
        val snapshot = """{"type":"snapshot","callId":"call-1","pairId":"pair-1","callerUserId":"user-b","startedAtEpochMillis":1}"""
        listener.captured.onMessage(socket, snapshot)
        assertThat(repository.observeState().first().incomingCallCandidate?.callId).isEqualTo("call-1")
        val refresh = async { repository.refreshActiveCall() }
        runCurrent()
        listener.captured.onMessage(socket, """{"type":"call_ended","callId":"call-1"}""")
        response.complete(ActiveCallResponseDto(incomingCall()))
        refresh.await()
        assertThat(repository.observeState().first().incomingCallCandidate).isNull()
        listener.captured.onMessage(socket, snapshot)
        assertThat(repository.observeState().first().incomingCallCandidate).isNull()
        repository.stop()
    }

    @Test
    fun `slow empty poll cannot remove a newer incoming call event`() = runTest {
        val response = CompletableDeferred<ActiveCallResponseDto>()
        val api = mockk<PurrCallApi> {
            io.mockk.coEvery { getActiveCall() } coAnswers { response.await() }
        }
        val listener = slot<WebSocketListener>()
        val socket = mockk<WebSocket>(relaxed = true)
        val client = mockk<OkHttpClient> {
            every { newWebSocket(any(), capture(listener)) } returns socket
        }
        val repository = ApiRealtimeRepository(
            okHttpClient = client,
            json = Json,
            sessionTokenHolder = SessionTokenHolder().apply { update("token", "refresh", "user-a") },
            api = api,
            realtimeUrl = "wss://example.invalid/realtime",
            applicationScope = backgroundScope,
        )
        repository.start()
        val refresh = async { repository.refreshActiveCall() }
        runCurrent()
        listener.captured.onMessage(socket,
            """{"type":"call_started","callId":"call-1","pairId":"pair-1","callerUserId":"user-b","startedAtEpochMillis":1}""",
        )
        response.complete(ActiveCallResponseDto(null))
        refresh.await()
        assertThat(repository.observeState().first().incomingCallCandidate?.callId).isEqualTo("call-1")
        repository.stop()
    }

    private fun incomingCall() = ActiveCallDto(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "user-b",
        isIncoming = true,
        startedAtEpochMillis = 1L,
    )
}
