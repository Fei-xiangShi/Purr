package life.fxs.purr.data.call.runtime

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.NoOpPurrLogger
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
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit

        runtime.execute(connectCommand())

        coVerifyOrder {
            callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing)
            systemCallController.startCall(any())
            systemCallController.activateCall("call-1")
            callAudioSessionController.activate()
            mediaCallPort.execute(any<MediaCallCommand.Connect>())
        }
    }

    @Test
    fun `incoming direction and remote identity reach the system call boundary`() = runTest {
        val runtime = runtime()
        val command = connectCommand(
            direction = CallDirection.Incoming,
            remoteDisplayName = "Partner",
        )
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Incoming) } returns Unit
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
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any()) } returns Unit

        runtime.execute(connectCommand())
        runtime.execute(connectCommand())

        coVerify(exactly = 1) { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing) }
        coVerify(exactly = 1) { systemCallController.startCall(any()) }
        coVerify(exactly = 1) { systemCallController.activateCall("call-1") }
        coVerify(exactly = 1) { callAudioSessionController.activate() }
        coVerify(exactly = 1) { mediaCallPort.execute(any<MediaCallCommand.Connect>()) }
    }

    @Test
    fun `termination waits for cancellation-suppressing stage cleanup and does not continue setup`() = runTest {
        val runtime = runtime()
        val signal = CallTerminationSignal()
        val serviceStarted = CompletableDeferred<Unit>()
        val releaseServiceStart = CompletableDeferred<Unit>()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing) } coAnswers {
            serviceStarted.complete(Unit)
            withContext(NonCancellable) { releaseServiceStart.await() }
        }
        coEvery { callAudioSessionController.release() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        val connect = async { runCatching { runtime.execute(connectCommand(signal)) } }
        serviceStarted.await()
        signal.request()
        assertThat(connect.isCompleted).isFalse()

        releaseServiceStart.complete(Unit)
        val failure = connect.await().exceptionOrNull()

        assertThat(failure).isInstanceOf(CallTerminationException::class.java)
        coVerify(exactly = 0) { systemCallController.startCall(any()) }
        coVerify(exactly = 1) { callAudioSessionController.release() }
        coVerify(exactly = 1) { callServiceController.stopForegroundCall("call-1") }
    }

    @Test
    fun `media connection failure releases foreground service and audio session`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall(any(), any(), any()) } returns Unit
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
        coEvery { callServiceController.startForegroundCall(any(), any(), any()) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        val connectFailure = IllegalStateException("connect failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } throws connectFailure
        coEvery { callAudioSessionController.release() } throws cleanupFailure
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        val result = runCatching { runtime.execute(connectCommand()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).isEqualTo(connectFailure.message)
        assertThat(result.exceptionOrNull()?.suppressed?.single()).isSameInstanceAs(cleanupFailure)
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
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing) } returns Unit
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
    fun `mute and unmute change only media transmission`() = runTest {
        val runtime = connectedRuntime()
        coEvery { mediaCallPort.execute(match { it is MediaCallCommand.SetMuted }) } returns Unit

        runtime.execute(MediaCallCommand.SetMuted("call-1", muted = true))
        runtime.execute(MediaCallCommand.SetMuted("call-1", muted = false))

        coVerify(exactly = 2) { mediaCallPort.execute(match { it is MediaCallCommand.SetMuted }) }
        coVerify(exactly = 0) { audioRouteController.selectRoute(any()) }
        coVerify(exactly = 0) { audioRouteController.selectDefaultRoute() }
        coVerify(exactly = 0) { audioRouteController.releaseCallRoute() }
    }

    @Test
    fun `livekit stage may use its full 25 second budget`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall(any(), any(), any()) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } coAnswers { delay(24_000L) }

        runtime.execute(connectCommand())

        coVerify(exactly = 0) { callAudioSessionController.release() }
        assertThat(testScheduler.currentTime).isEqualTo(24_000L)
    }

    @Test
    fun `livekit stage times out after 25 seconds and releases resources`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall(any(), any(), any()) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } coAnswers { delay(26_000L) }
        coEvery { callAudioSessionController.release() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        val failure = runCatching { runtime.execute(connectCommand()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(kotlinx.coroutines.TimeoutCancellationException::class.java)
        assertThat(testScheduler.currentTime).isEqualTo(25_000L)
        coVerify(exactly = 1) { callAudioSessionController.release() }
    }

    @Test
    fun `platform stage times out after 5 seconds`() = runTest {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall(any(), any(), any()) } coAnswers { delay(6_000L) }
        coEvery { callAudioSessionController.release() } returns Unit
        coEvery { callServiceController.stopForegroundCall("call-1") } returns Unit

        val failure = runCatching { runtime.execute(connectCommand()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(kotlinx.coroutines.TimeoutCancellationException::class.java)
        assertThat(testScheduler.currentTime).isEqualTo(5_000L)
        coVerify(exactly = 0) { systemCallController.startCall(any()) }
    }

    @Test
    fun `rejoin runs only the media stage and keeps resources on failure`() = runTest {
        val runtime = connectedRuntime()
        coEvery { mediaCallPort.execute(match { it is MediaCallCommand.Rejoin }) } throws
            java.io.IOException("offline")
        val rejoin = MediaCallCommand.Rejoin(
            callId = "call-1",
            connection = CallMediaConnection("wss://example.invalid", "token-2"),
            microphoneEnabled = true,
        )

        val failure = runCatching { runtime.execute(rejoin) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        coVerify(exactly = 1) { callServiceController.startForegroundCall(any(), any(), any()) }
        coVerify(exactly = 1) { systemCallController.startCall(any()) }
        coVerify(exactly = 1) { callAudioSessionController.activate() }
        coVerify(exactly = 0) { callAudioSessionController.release() }
        coVerify(exactly = 0) { systemCallController.disconnectCall(any()) }
        coVerify(exactly = 0) { callServiceController.stopForegroundCall(any()) }
    }

    @Test
    fun `rejoin without an active runtime is rejected`() = runTest {
        val runtime = runtime()
        val failure = runCatching {
            runtime.execute(
                MediaCallCommand.Rejoin(
                    callId = "call-1",
                    connection = CallMediaConnection("wss://example.invalid", "token"),
                    microphoneEnabled = true,
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 0) { mediaCallPort.execute(any()) }
    }

    private suspend fun connectedRuntime(): CallRuntimeControllerImpl {
        val runtime = runtime()
        coEvery { callServiceController.startForegroundCall("call-1", "pair-1", CallDirection.Outgoing) } returns Unit
        coEvery { callAudioSessionController.activate() } returns Unit
        coEvery { mediaCallPort.execute(any<MediaCallCommand.Connect>()) } returns Unit
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
            logger = NoOpPurrLogger,
        )
    }

    private fun connectCommand(
        terminationSignal: CallTerminationSignal = CallTerminationSignal(),
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
        terminationSignal = terminationSignal,
    )
}
