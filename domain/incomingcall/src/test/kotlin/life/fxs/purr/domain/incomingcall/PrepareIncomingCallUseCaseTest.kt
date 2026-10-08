package life.fxs.purr.domain.incomingcall

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.account.repository.RealtimeRepository
import life.fxs.purr.domain.account.usecase.ConsumeIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.model.ActiveCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import org.junit.Test

class PrepareIncomingCallUseCaseTest {
    private val callRepository = mockk<CallRepository>()
    private val realtimeRepository = mockk<RealtimeRepository>(relaxed = true)
    private val realtimeState = MutableStateFlow(
        RealtimeState(activeCall = ActiveCall("call-1", "pair-1", true)),
    )
    init {
        every { realtimeRepository.observeState() } returns realtimeState
        coEvery { realtimeRepository.refreshActiveCall() } returns AppResult.Success(Unit)
    }
    private val useCase = PrepareIncomingCallUseCase(
        prepareCallSession = PrepareCallSessionUseCase(callRepository),
        consumeIncomingCall = ConsumeIncomingCallUseCase(realtimeRepository),
        refreshActiveCall = RefreshActiveCallUseCase(realtimeRepository),
        observeRealtimeState = ObserveRealtimeStateUseCase(realtimeRepository),
    )

    @Test
    fun `ended or replaced call cannot acquire credentials for the stale identity`() = runTest {
        listOf(null, ActiveCall("call-2", "pair-1", true)).forEach { active ->
            realtimeState.value = RealtimeState(activeCall = active)
            val result = useCase(params())
            assertThat(result).isEqualTo(AppResult.Failure(
                AppError.Validation("这通电话已结束，请返回首页重新拨打"),
            ))
        }
        coVerify(exactly = 0) { callRepository.prepareCall(any()) }
        verify(exactly = 2) { realtimeRepository.consumeIncomingCall("call-1") }
        verify(exactly = 0) { realtimeRepository.consumeIncomingCall("call-2") }
    }

    @Test
    fun `failed revalidation preserves retryable candidate and does not prepare`() = runTest {
        val failure = AppResult.Failure(AppError.Network("offline"))
        coEvery { realtimeRepository.refreshActiveCall() } returns failure
        assertThat(useCase(params())).isEqualTo(failure)
        coVerify(exactly = 0) { callRepository.prepareCall(any()) }
        verify(exactly = 0) { realtimeRepository.consumeIncomingCall(any()) }
    }

    @Test
    fun `room ending during preparation refreshes the stale home state`() = runTest {
        coEvery { callRepository.prepareCall(any()) } returns AppResult.Failure(
            AppError.Validation("Incoming call is no longer active: call-1"),
        )
        var refreshes = 0
        coEvery { realtimeRepository.refreshActiveCall() } coAnswers {
            if (++refreshes == 2) realtimeState.value = RealtimeState()
            AppResult.Success(Unit)
        }
        assertThat(useCase(params())).isEqualTo(AppResult.Failure(
            AppError.Validation("这通电话已结束，请返回首页重新拨打"),
        ))
        assertThat(realtimeState.value.activeCall).isNull()
        verify(exactly = 1) { realtimeRepository.consumeIncomingCall("call-1") }
    }

    @Test
    fun `ended status after credentials were issued also clears expired presentation`() = runTest {
        coEvery { callRepository.prepareCall(any()) } coAnswers {
            realtimeState.value = RealtimeState()
            AppResult.Failure(AppError.Unexpected(IllegalStateException("Call has already ended")))
        }
        assertThat(useCase(params())).isEqualTo(AppResult.Failure(
            AppError.Validation("这通电话已结束，请返回首页重新拨打"),
        ))
        verify(exactly = 1) { realtimeRepository.consumeIncomingCall("call-1") }
    }

    @Test
    fun `outgoing participant can explicitly resume the same active room`() = runTest {
        realtimeState.value = RealtimeState(activeCall = ActiveCall("call-1", "pair-1", false))
        val request = params().copy(direction = CallDirection.Outgoing)
        coEvery { callRepository.prepareCall(request) } returns AppResult.Success(session())
        assertThat(useCase(request)).isInstanceOf(AppResult.Success::class.java)
        coVerify(exactly = 1) { callRepository.prepareCall(request) }
    }

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
