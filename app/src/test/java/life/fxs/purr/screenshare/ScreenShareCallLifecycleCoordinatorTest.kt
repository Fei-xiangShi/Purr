package life.fxs.purr.screenshare

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.repository.ScreenShareRepository
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenShareCallLifecycleCoordinatorTest {
    private val sessions = MutableStateFlow<CallSession?>(null)
    private val callRepository = mockk<CallRepository>()
    private val screenShareRepository = mockk<ScreenShareRepository>()

    @Test
    fun `rejoining the same business call creates a new cleanup boundary`() = runTest {
        every { callRepository.observeCallSession() } returns sessions
        coEvery { screenShareRepository.stop("call-1") } returns AppResult.Success(null)
        val coordinator = ScreenShareCallLifecycleCoordinator(callRepository, screenShareRepository, backgroundScope)
        coordinator.start()
        repeat(2) {
            sessions.value = sampleSession(CallConnectionState.Connected)
            runCurrent()
            sessions.value = sampleSession(CallConnectionState.Disconnected)
            runCurrent()
        }
        coVerify(exactly = 2) { screenShareRepository.stop("call-1") }
    }

    @Test
    fun `slow server cleanup cannot block the next calls local cleanup`() = runTest {
        every { callRepository.observeCallSession() } returns sessions
        coEvery { screenShareRepository.stop("call-1") } coAnswers { awaitCancellation() }
        coEvery { screenShareRepository.stop("call-2") } returns AppResult.Success(null)
        ScreenShareCallLifecycleCoordinator(callRepository, screenShareRepository, backgroundScope).start()
        sessions.value = sampleSession(CallConnectionState.Disconnected)
        runCurrent()
        sessions.value = sampleSession(CallConnectionState.Connected).copy(callId = "call-2")
        runCurrent()
        sessions.value = sampleSession(CallConnectionState.Disconnected).copy(callId = "call-2")
        runCurrent()
        coVerify(exactly = 1) { screenShareRepository.stop("call-2") }
    }

    @Test
    fun `terminal call independently cleans its screen share once`() = runTest {
        every { callRepository.observeCallSession() } returns sessions
        coEvery { screenShareRepository.stop("call-1") } returns AppResult.Success(null)
        val coordinator = ScreenShareCallLifecycleCoordinator(
            callRepository = callRepository,
            screenShareRepository = screenShareRepository,
            applicationScope = backgroundScope,
        )

        coordinator.start()
        coordinator.start()
        sessions.value = sampleSession(CallConnectionState.Connected)
        runCurrent()
        sessions.value = sampleSession(CallConnectionState.Terminating)
        runCurrent()
        sessions.value = sampleSession(CallConnectionState.Disconnected)
        runCurrent()

        coVerify(exactly = 1) { screenShareRepository.stop("call-1") }
    }

    private fun sampleSession(connectionState: CallConnectionState) = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self", remote = "partner"),
        roomName = "room-1",
        connectionState = connectionState,
    )
}
