package life.fxs.purr.platform.push

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallWakeRecoveryTest {
    @Test
    fun `waits for restored authentication before realtime refresh`() = runTest {
        val auth = MutableStateFlow<AuthSession?>(null)
        val observeAuth = mockk<ObserveAuthSessionUseCase>()
        val startRealtime = mockk<StartRealtimeUpdatesUseCase>()
        val refreshActiveCall = mockk<RefreshActiveCallUseCase>()
        every { observeAuth.invoke() } returns auth
        every { startRealtime.invoke() } just Runs
        coEvery { refreshActiveCall.invoke() } returns AppResult.Success(Unit)
        val recovery = IncomingCallWakeRecovery(observeAuth, startRealtime, refreshActiveCall)

        val result = async { recovery.recover() }
        runCurrent()
        verify(exactly = 0) { startRealtime.invoke() }

        auth.value = session()
        runCurrent()

        assertThat(result.await())
            .isEqualTo(IncomingCallWakeRecoveryResult.Refreshed(AppResult.Success(Unit)))
        verify(exactly = 1) { startRealtime.invoke() }
    }

    private fun session() = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        self = SelfProfile("user-a", "User A"),
    )
}
