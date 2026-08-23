package life.fxs.purr.domain.incomingcall

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.account.repository.RealtimeRepository
import life.fxs.purr.domain.account.usecase.ConsumeIncomingCallUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import org.junit.Test

class PrepareIncomingCallUseCaseTest {
    private val callRepository = mockk<CallRepository>()
    private val realtimeRepository = mockk<RealtimeRepository>(relaxed = true)
    private val useCase = PrepareIncomingCallUseCase(
        prepareCallSession = PrepareCallSessionUseCase(callRepository),
        consumeIncomingCall = ConsumeIncomingCallUseCase(realtimeRepository),
    )

    @Test
    fun `successful exact preparation consumes the incoming candidate`() = runTest {
        val params = params()
        coEvery { callRepository.prepareCall(params) } returns AppResult.Success(session())

        val result = useCase(params)

        assertThat(result).isEqualTo(AppResult.Success(session()))
        verify(exactly = 1) { realtimeRepository.consumeIncomingCall("call-1") }
    }

    @Test
    fun `failed preparation keeps the incoming candidate retryable`() = runTest {
        val params = params()
        coEvery { callRepository.prepareCall(params) } returns
            AppResult.Failure(AppError.Network("offline"))

        val result = useCase(params)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        verify(exactly = 0) { realtimeRepository.consumeIncomingCall(any()) }
    }

    @Test
    fun `missing expected identity fails before repository access`() = runTest {
        val result = useCase(params().copy(callId = ""))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        io.mockk.coVerify(exactly = 0) { callRepository.prepareCall(any()) }
    }

    @Test
    fun `mismatched successful session is rejected without consuming candidate`() = runTest {
        val params = params()
        coEvery { callRepository.prepareCall(params) } returns
            AppResult.Success(session().copy(callId = "call-other"))

        val result = useCase(params)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        verify(exactly = 0) { realtimeRepository.consumeIncomingCall(any()) }
    }

    private fun params() = CallPreparationRequest.Existing(
        pairId = "pair-1",
        callId = "call-1",
        recordingConsent = true,
        remoteDisplayName = "Partner",
        direction = CallDirection.Incoming,
    )

    private fun session() = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self"),
        roomName = "room-1",
        remoteDisplayName = "Partner",
        direction = CallDirection.Incoming,
    )
}
