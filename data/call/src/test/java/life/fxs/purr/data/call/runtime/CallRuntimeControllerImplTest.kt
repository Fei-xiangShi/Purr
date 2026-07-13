package life.fxs.purr.data.call.runtime

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioFocusManager
import life.fxs.purr.core.media.service.CallServiceController
import org.junit.Test

class CallRuntimeControllerImplTest {
    private val mediaCallPort = mockk<MediaCallPort>()
    private val audioRouteController = mockk<AudioRouteController>()
    private val callAudioFocusManager = mockk<CallAudioFocusManager>()
    private val callServiceController = mockk<CallServiceController>()

    @Test
    fun `connect establishes Android call runtime before media`() = runTest {
        every { mediaCallPort.events } returns emptyFlow()
        coEvery { callAudioFocusManager.requestFocus() } returns true
        coEvery { audioRouteController.releaseCallRoute() } returns Unit
        coEvery { audioRouteController.restorePreferredRoute() } returns Unit
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit
        val runtime = runtime()

        runtime.execute(connectCommand())

        coVerifyOrder {
            callServiceController.startForegroundCall("call-1", "pair-1")
            callAudioFocusManager.requestFocus()
            audioRouteController.restorePreferredRoute()
            mediaCallPort.execute(connectCommand())
        }
    }

    @Test
    fun `duplicate connect for the active call is idempotent`() = runTest {
        every { mediaCallPort.events } returns emptyFlow()
        coEvery { callAudioFocusManager.requestFocus() } returns true
        coEvery { audioRouteController.releaseCallRoute() } returns Unit
        coEvery { audioRouteController.restorePreferredRoute() } returns Unit
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit
        val runtime = runtime()

        runtime.execute(connectCommand())
        runtime.execute(connectCommand())

        coVerify(exactly = 1) { callServiceController.startForegroundCall("call-1", "pair-1") }
        coVerify(exactly = 1) { callAudioFocusManager.requestFocus() }
        coVerify(exactly = 1) { mediaCallPort.execute(connectCommand()) }
    }

    @Test
    fun `media connection failure releases foreground service and audio focus`() = runTest {
        every { mediaCallPort.events } returns emptyFlow()
        coEvery { callAudioFocusManager.requestFocus() } returns true
        coEvery { audioRouteController.releaseCallRoute() } returns Unit
        coEvery { audioRouteController.restorePreferredRoute() } returns Unit
        coEvery { callServiceController.startForegroundCall(any(), any()) } returns Unit
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } throws
            IllegalStateException("connect failed")
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit
        coEvery { callAudioFocusManager.abandonFocus() } returns Unit
        val runtime = runtime()

        runCatching { runtime.execute(connectCommand()) }

        coVerifyOrder {
            callAudioFocusManager.abandonFocus()
            callServiceController.stopForegroundCall("call-1")
        }
    }

    @Test
    fun `connect keeps original failure and attaches cleanup failure`() = runTest {
        every { mediaCallPort.events } returns emptyFlow()
        coEvery { callAudioFocusManager.requestFocus() } returns true
        coEvery { audioRouteController.releaseCallRoute() } returns Unit
        coEvery { audioRouteController.restorePreferredRoute() } returns Unit
        coEvery { callServiceController.startForegroundCall(any(), any()) } returns Unit
        val connectFailure = IllegalStateException("connect failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } throws connectFailure
        coEvery { callAudioFocusManager.abandonFocus() } throws cleanupFailure
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        val result = runCatching { runtime().execute(connectCommand()) }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(connectFailure)
        assertThat(connectFailure.suppressed.single()).isSameInstanceAs(cleanupFailure)
        coVerify(exactly = 1) { callServiceController.stopForegroundCall("call-1") }
    }

    @Test
    fun `disconnect is idempotent when runtime was not acquired`() = runTest {
        every { mediaCallPort.events } returns emptyFlow()
        coEvery { audioRouteController.releaseCallRoute() } returns Unit
        coEvery { callAudioFocusManager.abandonFocus() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit
        val runtime = runtime()

        runtime.execute(MediaCallCommand.Disconnect("call-1"))

        coVerify(exactly = 1) { audioRouteController.releaseCallRoute() }
        coVerify(exactly = 1) { callAudioFocusManager.abandonFocus() }
        coVerify(exactly = 1) { callServiceController.stopForegroundCall("call-1") }
    }

    @Test
    fun `disconnect releases media route focus and foreground service`() = runTest {
        every { mediaCallPort.events } returns emptyFlow()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { callAudioFocusManager.requestFocus() } returns true
        coEvery { audioRouteController.restorePreferredRoute() } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit
        coEvery { audioRouteController.releaseCallRoute() } returns Unit
        coEvery { callAudioFocusManager.abandonFocus() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit
        val runtime = runtime()
        runtime.execute(connectCommand())

        runtime.execute(MediaCallCommand.Disconnect("call-1"))

        coVerifyOrder {
            mediaCallPort.execute(MediaCallCommand.Disconnect("call-1"))
            audioRouteController.releaseCallRoute()
            callAudioFocusManager.abandonFocus()
            callServiceController.stopForegroundCall("call-1")
        }
    }

    private fun runtime() = CallRuntimeControllerImpl(
        mediaCallPort = mediaCallPort,
        audioRouteController = audioRouteController,
        callAudioFocusManager = callAudioFocusManager,
        callServiceController = callServiceController,
    )

    private fun connectCommand() = MediaCallCommand.Connect(
        callId = "call-1",
        pairId = "pair-1",
        localIdentity = "self",
        connection = CallMediaConnection(
            wsUrl = "wss://example.invalid",
            accessToken = "token",
        ),
    )
}
