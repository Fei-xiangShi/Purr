package life.fxs.purr.realtime

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import life.fxs.purr.domain.account.usecase.StopRealtimeUpdatesUseCase
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSessionCoordinatorTest {
    @Test
    fun `authenticated session exclusively owns realtime and recovery lifecycle`() = runTest {
        val auth = MutableStateFlow<AuthSession?>(null)
        val observeAuth = mockk<ObserveAuthSessionUseCase>()
        val startRealtime = mockk<StartRealtimeUpdatesUseCase>()
        val stopRealtime = mockk<StopRealtimeUpdatesUseCase>()
        val refreshActiveCall = mockk<RefreshActiveCallUseCase>()
        every { observeAuth.invoke() } returns auth
        every { startRealtime.invoke() } just Runs
        every { stopRealtime.invoke() } just Runs
        coEvery { refreshActiveCall.invoke() } returns AppResult.Success(Unit)
        val coordinator = RealtimeSessionCoordinator(
            observeAuthSession = observeAuth,
            startRealtimeUpdates = startRealtime,
            stopRealtimeUpdates = stopRealtime,
            refreshActiveCall = refreshActiveCall,
            applicationScope = backgroundScope,
        )

        coordinator.start()
        coordinator.start()
        runCurrent()
        verify(exactly = 1) { observeAuth.invoke() }
        verify(exactly = 1) { stopRealtime.invoke() }

        auth.value = session()
        runCurrent()
        verify(exactly = 1) { startRealtime.invoke() }
        coVerify(exactly = 1) { refreshActiveCall.invoke() }

        advanceTimeBy(10_000L)
        runCurrent()
        coVerify(exactly = 2) { refreshActiveCall.invoke() }

        auth.value = null
        runCurrent()
        verify(exactly = 3) { stopRealtime.invoke() }
        advanceTimeBy(20_000L)
        runCurrent()
        coVerify(exactly = 2) { refreshActiveCall.invoke() }
    }

    private fun session() = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )
}
