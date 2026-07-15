package life.fxs.purr.data.call.runtime

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioSessionController
import life.fxs.purr.core.media.audio.CallAudioSessionState
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.model.CallDirection
import org.junit.Test

class CallRuntimeControllerImplTest {
    private val mediaCallPort = mockk<MediaCallPort>()
    private val audioRouteController = mockk<AudioRouteController>()
    private val callAudioSessionController = mockk<CallAudioSessionController>()
    private val callServiceController = mockk<CallServiceController>()
    private val systemCallController = mockk<SystemCallController>()

    @Test
    fun `connect establishes Android call session before media`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit

        runtime.execute(connectCommand())

        coVerifyOrder {
            callServiceController.startForegroundCall("call-1", "pair-1")
            systemCallController.startCall(any())
            systemCallController.activateCall("call-1")
            callAudioSessionController.activate()
            mediaCallPort.execute(connectCommand())
        }
    }

    @Test
    fun `incoming direction and remote identity reach the system call boundary`() = runTest {
        val runtime = runtime()
        val command = connectCommand(
            direction = CallDirection.Incoming,
            remoteDisplayName = "Partner",
        )
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(command) } returns Unit

        runtime.execute(command)

        coVerify(exactly = 1) {
            systemCallController.startCall(
                match { descriptor ->
                    descriptor.callId == "call-1" &&
                        descriptor.pairId == "pair-1" &&
                        descriptor.remoteDisplayName == "Partner" &&
                        descriptor.direction == CallDirection.Incoming
                },
            )
        }
        coVerify(exactly = 1) { systemCallController.activateCall("call-1") }
    }

    @Test
    fun `duplicate connect for the active call is idempotent`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit

        runtime.execute(connectCommand())
        runtime.execute(connectCommand())

        coVerify(exactly = 1) { callServiceController.startForegroundCall("call-1", "pair-1") }
        coVerify(exactly = 1) { systemCallController.startCall(any()) }
        coVerify(exactly = 1) { systemCallController.activateCall("call-1") }
        coVerify(exactly = 1) { callAudioSessionController.activate() }
        coVerify(exactly = 1) { mediaCallPort.execute(connectCommand()) }
    }

    @Test
    fun `media connection failure releases foreground service and audio session`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall(any(), any()) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } throws
            IllegalStateException("connect failed")
        coEvery { callAudioSessionController.release() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        runCatching { runtime.execute(connectCommand()) }

        coVerifyOrder {
            callAudioSessionController.release()
            systemCallController.disconnectCall("call-1")
            callServiceController.stopForegroundCall("call-1")
        }
    }

    @Test
    fun `connect keeps original failure and attaches cleanup failure`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall(any(), any()) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        val connectFailure = IllegalStateException("connect failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } throws connectFailure
        coEvery { callAudioSessionController.release() } throws cleanupFailure
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        val result = runCatching { runtime.execute(connectCommand()) }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(connectFailure)
        assertThat(connectFailure.suppressed.single()).isSameInstanceAs(cleanupFailure)
        coVerify(exactly = 1) { callServiceController.stopForegroundCall("call-1") }
    }

    @Test
    fun `disconnect is idempotent when runtime was not acquired`() = runTest {
        val runtime = runtime()
        coEvery { callAudioSessionController.release() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        runtime.execute(MediaCallCommand.Disconnect("call-1"))

        coVerify(exactly = 1) { callAudioSessionController.release() }
        coVerify(exactly = 1) { systemCallController.disconnectCall("call-1") }
        coVerify(exactly = 1) { callServiceController.stopForegroundCall("call-1") }
    }

    @Test
    fun `disconnect releases media session and foreground service`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit
        coEvery { callAudioSessionController.release() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit
        runtime.execute(connectCommand())

        runtime.execute(MediaCallCommand.Disconnect("call-1"))

        coVerifyOrder {
            mediaCallPort.execute(MediaCallCommand.Disconnect("call-1"))
            callAudioSessionController.release()
            systemCallController.disconnectCall("call-1")
            callServiceController.stopForegroundCall("call-1")
        }
    }

    @Test
    fun `mute changes only media transmission`() = runTest {
        val runtime = connectedRuntime()
        val command = MediaCallCommand.SetMuted("call-1", muted = true)
        coEvery { mediaCallPort.execute(command) } returns Unit

        runtime.execute(command)

        coVerify(exactly = 1) { mediaCallPort.execute(command) }
        coVerify(exactly = 0) { audioRouteController.selectRoute(any()) }
        coVerify(exactly = 0) { audioRouteController.selectDefaultRoute() }
        coVerify(exactly = 0) { audioRouteController.releaseCallRoute() }
    }

    @Test
    fun `unmute changes only media transmission`() = runTest {
        val runtime = connectedRuntime()
        val command = MediaCallCommand.SetMuted("call-1", muted = false)
        coEvery { mediaCallPort.execute(command) } returns Unit

        runtime.execute(command)

        coVerify(exactly = 1) { mediaCallPort.execute(command) }
        coVerify(exactly = 0) { audioRouteController.selectRoute(any()) }
        coVerify(exactly = 0) { audioRouteController.selectDefaultRoute() }
        coVerify(exactly = 0) { audioRouteController.releaseCallRoute() }
    }

    @Test
    fun `failed unmute does not mutate the audio session or route`() = runTest {
        val runtime = connectedRuntime()
        val command = MediaCallCommand.SetMuted("call-1", muted = false)
        val failure = IllegalStateException("provider rejected unmute")
        coEvery { mediaCallPort.execute(command) } throws failure

        val result = runCatching { runtime.execute(command) }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        coVerify(exactly = 1) { mediaCallPort.execute(command) }
        coVerify(exactly = 0) { audioRouteController.selectRoute(any()) }
        coVerify(exactly = 0) { audioRouteController.selectDefaultRoute() }
        coVerify(exactly = 0) { audioRouteController.releaseCallRoute() }
    }

    private suspend fun connectedRuntime(): CallRuntimeControllerImpl {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1") } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(connectCommand()) } returns Unit
        runtime.execute(connectCommand())
        return runtime
    }

    private fun runtime(): CallRuntimeControllerImpl {
        every { mediaCallPort.events } returns emptyFlow()
        every { systemCallController.events } returns emptyFlow()
        coEvery { systemCallController.startCall(any()) } returns Unit
        coEvery { systemCallController.activateCall(any()) } returns Unit
        coEvery { systemCallController.disconnectCall(any()) } returns Unit
        every { callAudioSessionController.state } returns MutableStateFlow(
            CallAudioSessionState.Active,
        )
        return CallRuntimeControllerImpl(
            mediaCallPort = mediaCallPort,
            audioRouteController = audioRouteController,
            callAudioSessionController = callAudioSessionController,
            callServiceController = callServiceController,
            systemCallController = systemCallController,
        )
    }

    private fun connectCommand(
        direction: CallDirection = CallDirection.Outgoing,
        remoteDisplayName: String = "Purr",
    ) = MediaCallCommand.Connect(
        callId = "call-1",
        pairId = "pair-1",
        localIdentity = "self",
        connection = CallMediaConnection(
            wsUrl = "wss://example.invalid",
            accessToken = "token",
        ),
        remoteDisplayName = remoteDisplayName,
        direction = direction,
    )
}
