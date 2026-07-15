package life.fxs.purr.platform.incomingcall

import io.mockk.coEvery
import io.mockk.coVerify
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

class IncomingCallRecoveryTest {
    private val observeAuthSession = mockk<ObserveAuthSessionUseCase>()
    private val startRealtimeUpdates = mockk<StartRealtimeUpdatesUseCase>()
    private val refreshActiveCall = mockk<RefreshActiveCallUseCase>()

    @Test
    fun `authenticated recovery starts realtime before refreshing active call`() = runTest {
        every { observeAuthSession() } returns MutableStateFlow(authSession())
        every { startRealtimeUpdates() } just runs
        coEvery { refreshActiveCall() } returns AppResult.Success(Unit)
        val recovery = IncomingCallRecovery(
            observeAuthSession = observeAuthSession,
            startRealtimeUpdates = startRealtimeUpdates,
            refreshActiveCall = refreshActiveCall,
        )

        recovery.refreshAfterProcessRecreation()

        coVerifyOrder {
            startRealtimeUpdates()
            refreshActiveCall()
        }
        coVerify(exactly = 1) { refreshActiveCall() }
    }

    private fun authSession() = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )
}
