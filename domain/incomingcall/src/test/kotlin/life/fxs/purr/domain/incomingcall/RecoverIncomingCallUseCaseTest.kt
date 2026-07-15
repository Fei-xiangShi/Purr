package life.fxs.purr.domain.incomingcall

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import org.junit.Test

class RecoverIncomingCallUseCaseTest {
    @Test
    fun `authenticated recovery starts realtime before refreshing active call`() = runTest {
        val observeAuthSession = mockk<ObserveAuthSessionUseCase>()
        val startRealtimeUpdates = mockk<StartRealtimeUpdatesUseCase>()
        val refreshActiveCall = mockk<RefreshActiveCallUseCase>()
        every { observeAuthSession() } returns MutableStateFlow(authSession())
        every { startRealtimeUpdates() } just runs
        coEvery { refreshActiveCall() } returns AppResult.Success(Unit)
        val recovery = RecoverIncomingCallUseCase(
            observeAuthSession = observeAuthSession,
            startRealtimeUpdates = startRealtimeUpdates,
            refreshActiveCall = refreshActiveCall,
        )

        val result = recovery()

        assertThat(result).isEqualTo(IncomingCallRecoveryResult.Refreshed(AppResult.Success(Unit)))
        coVerifyOrder {
            startRealtimeUpdates()
            refreshActiveCall()
        }
    }

    private fun authSession() = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )
}
