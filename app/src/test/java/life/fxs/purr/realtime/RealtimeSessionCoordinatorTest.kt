package life.fxs.purr.realtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeRunningUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import life.fxs.purr.domain.account.usecase.StopRealtimeUpdatesUseCase
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.incomingcall.ApplicationVisibility
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSessionCoordinatorTest {
    private val auth = MutableStateFlow<AuthSession?>(null)
    private val foreground = MutableStateFlow(true)
    private val running = MutableStateFlow(false)
    private val call = MutableStateFlow<CallSession?>(null)
    private val realtime = MutableStateFlow(RealtimeState())

    private val startRealtime = mockk<StartRealtimeUpdatesUseCase>()
    private val stopRealtime = mockk<StopRealtimeUpdatesUseCase>()
    private val refreshActiveCall = mockk<RefreshActiveCallUseCase>()

    private val grace = RealtimeSessionCoordinator.BACKGROUND_SOCKET_GRACE.inWholeMilliseconds

    private fun TestScope.coordinator(): RealtimeSessionCoordinator {
        every { startRealtime.invoke() } answers { running.value = true }
        every { stopRealtime.invoke() } answers { running.value = false }
        coEvery { refreshActiveCall.invoke() } returns AppResult.Success(Unit)
        return RealtimeSessionCoordinator(
            observeAuthSession = mockk<ObserveAuthSessionUseCase>().also { every { it.invoke() } returns auth },
            startRealtimeUpdates = startRealtime,
            stopRealtimeUpdates = stopRealtime,
            refreshActiveCall = refreshActiveCall,
            observeRealtimeState = mockk<ObserveRealtimeStateUseCase>().also { every { it.invoke() } returns realtime },
            observeRealtimeRunning = mockk<ObserveRealtimeRunningUseCase>().also { every { it.invoke() } returns running },
            observeCallState = mockk<ObserveCallStateUseCase>().also { every { it.invoke() } returns call },
            applicationVisibility = object : ApplicationVisibility {
                override val isForeground = foreground
            },
            applicationScope = backgroundScope,
        )
    }

    @Test
    fun `authenticated foreground session owns realtime and polling`() = runTest {
        val coordinator = coordinator()
        coordinator.start()
        coordinator.start()
        runCurrent()
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

    @Test
    fun `background stops polling immediately and drops socket after grace`() = runTest {
        val coordinator = coordinator()
        auth.value = session()
        coordinator.start()
        runCurrent()
        coVerify(exactly = 1) { refreshActiveCall.invoke() }

        foreground.value = false
        runCurrent()
        advanceTimeBy(grace - 1)
        runCurrent()
        coVerify(exactly = 1) { refreshActiveCall.invoke() }
        verify(exactly = 1) { stopRealtime.invoke() }
        verify(exactly = 1) { startRealtime.invoke() }

        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 2) { stopRealtime.invoke() }
    }

    @Test
    fun `ongoing call or incoming candidate holds socket until released`() = runTest {
        val coordinator = coordinator()
        auth.value = session()
        coordinator.start()
        runCurrent()
        foreground.value = false
        call.value = callSession()
        runCurrent()
        advanceTimeBy(grace * 2)
        runCurrent()
        verify(exactly = 1) { stopRealtime.invoke() }

        realtime.value = RealtimeState(incomingCallCandidate = mockk<IncomingCall>())
        call.value = null
        runCurrent()
        advanceTimeBy(grace * 2)
        runCurrent()
        verify(exactly = 1) { stopRealtime.invoke() }

        realtime.value = RealtimeState()
        runCurrent()
        advanceTimeBy(grace)
        runCurrent()
        verify(exactly = 2) { stopRealtime.invoke() }
    }

    @Test
    fun `worker started socket in background gets a fresh grace window`() = runTest {
        val coordinator = coordinator()
        auth.value = session()
        coordinator.start()
        runCurrent()
        foreground.value = false
        runCurrent()
        advanceTimeBy(grace)
        runCurrent()
        verify(exactly = 2) { stopRealtime.invoke() }

        startRealtime.invoke()
        runCurrent()
        advanceTimeBy(grace - 1)
        runCurrent()
        verify(exactly = 2) { stopRealtime.invoke() }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 3) { stopRealtime.invoke() }
    }

    @Test
    fun `returning to foreground restarts socket and refreshes`() = runTest {
        val coordinator = coordinator()
        auth.value = session()
        coordinator.start()
        runCurrent()
        foreground.value = false
        runCurrent()
        advanceTimeBy(grace)
        runCurrent()
        verify(exactly = 1) { startRealtime.invoke() }
        coVerify(exactly = 1) { refreshActiveCall.invoke() }

        foreground.value = true
        runCurrent()
        verify(exactly = 2) { startRealtime.invoke() }
        coVerify(exactly = 2) { refreshActiveCall.invoke() }
    }

    private fun session() = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )

    private fun callSession() = mockk<CallSession> {
        every { connectionState } returns CallConnectionState.Connected
    }
}
