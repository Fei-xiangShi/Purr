package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhepPlaybackLifecycleTest {
    @Test
    fun `stop detaches pending setup and old callbacks cannot corrupt replacement`() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val lifecycle = WhepPlaybackLifecycle(backgroundScope) { _, listener ->
            FakeSession(listener, pending = true).also(sessions::add)
        }
        val old = request("old")
        val next = request("next")
        lifecycle.start(old)
        runCurrent()
        lifecycle.stop(old.shareId)
        lifecycle.start(next)
        runCurrent()

        assertThat(sessions[0].closed).isEqualTo(1)
        assertThat(sessions[0].listener.onFrame(1920, 1080)).isFalse()
        sessions[0].listener.onFailed("old failure")
        lifecycle.stop(old.shareId)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Connecting(next))
        assertThat(sessions[1].closed).isEqualTo(0)
        lifecycle.stop(next.shareId)
        runCurrent()
        assertThat(sessions[1].closed).isEqualTo(1)
    }

    @Test
    fun `failed attempt releases native session and identical credentials can be retried`() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val lifecycle = WhepPlaybackLifecycle(backgroundScope) { _, listener ->
            FakeSession(listener).also(sessions::add)
        }
        val request = request("same")
        lifecycle.start(request)
        runCurrent()
        sessions[0].listener.onFrame(720, 1280)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Live(request, 720, 1280))
        sessions[0].listener.onFailed("ICE failed")
        runCurrent()
        assertThat(sessions[0].closed).isEqualTo(1)

        lifecycle.start(request)
        runCurrent()
        sessions[0].listener.onBuffering()
        sessions[0].listener.onFrame(1920, 1080)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Connecting(request))
        sessions[1].listener.onBuffering()
        sessions[1].listener.onFrame(1080, 1920)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Live(request, 1080, 1920))
        lifecycle.stop(request.shareId)
        runCurrent()
    }

    private class FakeSession(val listener: WhepSessionListener, val pending: Boolean = false) : WhepSession {
        var closed = 0
        override suspend fun connect() { if (pending) awaitCancellation() }
        override fun close() { closed++ }
    }

    private fun request(id: String) = WhepPlaybackRequest("call-1", id, "https://media/$id", "token", Long.MAX_VALUE)
}
