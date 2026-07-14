package life.fxs.purr.data.account.realtime

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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

    private fun incomingCall() = ActiveCallDto(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "user-b",
        isIncoming = true,
        startedAtEpochMillis = 1L,
    )
}
