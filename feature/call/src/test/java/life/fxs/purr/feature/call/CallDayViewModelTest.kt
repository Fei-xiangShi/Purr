package life.fxs.purr.feature.call

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallHistoryEntry
import life.fxs.purr.domain.call.model.CallHistoryPage
import life.fxs.purr.domain.call.model.CallOutcome
import life.fxs.purr.domain.call.usecase.LoadCallHistoryUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDayViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val loadHistory = mockk<LoadCallHistoryUseCase>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads and appends day pages without duplicates`() = runTest(dispatcher) {
        val first = history("call-1", 2_000L)
        val second = history("call-2", 1_000L)
        coEvery { loadHistory(any(), any(), null) } returns AppResult.Success(
            CallHistoryPage(listOf(first), "next-page"),
        )
        coEvery { loadHistory(any(), any(), "next-page") } returns AppResult.Success(
            CallHistoryPage(listOf(first, second), null),
        )
        val viewModel = CallDayViewModel(
            SavedStateHandle(mapOf(CALL_HISTORY_DATE_ARG to "2026-07-12")),
            loadHistory,
        )
        runCurrent()

        viewModel.onIntent(CallDayIntent.LoadMore)
        runCurrent()

        assertThat(viewModel.state.value.calls).containsExactly(first, second).inOrder()
        assertThat(viewModel.state.value.nextCursor).isNull()
    }

    private fun history(callId: String, startedAt: Long) = CallHistoryEntry(
        callId = callId,
        direction = CallDirection.Outgoing,
        outcome = CallOutcome.Completed,
        requestedAtEpochMillis = startedAt - 1_000L,
        startedAtEpochMillis = startedAt,
        connectedAtEpochMillis = startedAt,
        endedAtEpochMillis = startedAt + 60_000L,
        ringingDurationMillis = 1_000L,
        durationMillis = 60_000L,
        recordingStatus = "stopped",
    )
}
