package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhepPlaybackLifecycleTest {
    @Test
    fun `only final active failure is reported and expected codec failures are excluded`() = runTest {
        val reported = mutableListOf<ScreenShareFailureCode>()
        val sessions = mutableListOf<FakeSession>()
        val lifecycle = WhepPlaybackLifecycle(backgroundScope, clock = { testScheduler.currentTime },
            failures = ScreenShareFailureReporter { _, _, code -> reported += code },
        ) { _, listener -> FakeSession(listener).also(sessions::add) }
        lifecycle.start(request("first"))
        runCurrent()
        sessions[0].listener.onFailed("unsupported", ScreenShareFailureCode.UnsupportedCodec)
        runCurrent()
        assertThat(reported).isEmpty()
        lifecycle.start(request("next"))
        runCurrent()
        sessions[0].listener.onFailed("late", ScreenShareFailureCode.IceConnection)
        sessions[1].listener.onFailed("failed", ScreenShareFailureCode.IceConnection)
        sessions[1].listener.onFailed("duplicate", ScreenShareFailureCode.IceConnection)
        runCurrent()
        assertThat(reported).containsExactly(ScreenShareFailureCode.IceConnection)
    }
    @Test
    fun `stop detaches pending setup and old callbacks cannot corrupt replacement`() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val lifecycle = WhepPlaybackLifecycle(backgroundScope, clock = { testScheduler.currentTime }) { _, listener ->
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
        val lifecycle = WhepPlaybackLifecycle(backgroundScope, clock = { testScheduler.currentTime }) { _, listener ->
            FakeSession(listener).also(sessions::add)
        }
        val request = request("same")
        lifecycle.start(request)
        runCurrent()
        sessions[0].listener.onFrame(720, 1280)
        advanceTimeBy(400)
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
        advanceTimeBy(400)
        sessions[1].listener.onFrame(1080, 1920)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Live(request, 1080, 1920))
        lifecycle.stop(request.shareId)
        runCurrent()
    }

    @Test fun `connected media stall buffers and only sustained frames restore live`() = runTest {
        lateinit var session: FakeSession
        val lifecycle = WhepPlaybackLifecycle(backgroundScope, clock = { testScheduler.currentTime }) { _, listener ->
            FakeSession(listener).also { session = it }
        }
        val request = request("watchdog")
        lifecycle.start(request)
        runCurrent()
        session.listener.onFrame(1920, 1080)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Buffering(request))
        // 5 fps static screen must remain healthy.
        repeat(10) { advanceTimeBy(200); session.listener.onFrame(1920, 1080) }
        assertThat(lifecycle.status.value).isInstanceOf(WhepPlaybackStatus.Live::class.java)
        advanceTimeBy(1_500)
        runCurrent()
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Buffering(request))
        session.listener.onFrame(1920, 1080)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Buffering(request))
        advanceTimeBy(200)
        session.listener.onFrame(1920, 1080)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Buffering(request))
        advanceTimeBy(200)
        session.listener.onFrame(1920, 1080)
        assertThat(lifecycle.status.value).isInstanceOf(WhepPlaybackStatus.Live::class.java)
        lifecycle.stop(null)
    }

    @Test fun `sustained stall reports once closes playback and cannot revive from a late frame`() = runTest {
        val reported = mutableListOf<ScreenShareFailureCode>()
        val sessions = mutableListOf<FakeSession>()
        val lifecycle = WhepPlaybackLifecycle(backgroundScope, clock = { testScheduler.currentTime },
            failures = ScreenShareFailureReporter { _, _, code -> reported += code },
        ) { _, listener -> FakeSession(listener).also(sessions::add) }
        val request = request("stall")
        lifecycle.start(request)
        runCurrent()
        sessions[0].listener.onFrame(720, 1280)
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(lifecycle.status.value).isInstanceOf(WhepPlaybackStatus.Failed::class.java)
        assertThat(reported).containsExactly(ScreenShareFailureCode.PlaybackStalled)
        assertThat(sessions[0].closed).isEqualTo(1)
        assertThat(sessions[0].listener.onFrame(720, 1280)).isFalse()
        lifecycle.start(request)
        runCurrent()
        sessions[0].listener.onBuffering()
        advanceTimeBy(1_000)
        assertThat(lifecycle.status.value).isEqualTo(WhepPlaybackStatus.Connecting(request))
        lifecycle.stop(null)
        runCurrent()
        advanceTimeBy(30_000)
        assertThat(reported).hasSize(1)
        assertThat(sessions[1].closed).isEqualTo(1)
    }

    private class FakeSession(val listener: WhepSessionListener, val pending: Boolean = false) : WhepSession {
        var closed = 0
        override suspend fun connect() { if (pending) awaitCancellation() }
        override fun close() { closed++ }
    }

    private fun request(id: String) = WhepPlaybackRequest("call-1", id, "https://media/$id", "token", Long.MAX_VALUE)
}
