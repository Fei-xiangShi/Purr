package life.fxs.purr.feature.call

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
import life.fxs.purr.domain.call.model.CallHistoryEntry
import life.fxs.purr.domain.call.model.CallHistoryPage
import life.fxs.purr.domain.call.usecase.LoadCallHistoryUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val loadHistory = mockk<LoadCallHistoryUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads and appends paged history without duplicates`() = runTest(dispatcher) {
        val first = history("call-1", 2_000L)
        val second = history("call-2", 1_000L)
        coEvery { loadHistory.invoke(null) } returns AppResult.Success(
            CallHistoryPage(listOf(first), "next-page"),
        )
        coEvery { loadHistory.invoke("next-page") } returns AppResult.Success(
            CallHistoryPage(listOf(first, second), null),
        )
        val viewModel = CallHistoryViewModel(loadHistory)
        runCurrent()

        assertThat(viewModel.state.value.calls).containsExactly(first)

        viewModel.onIntent(CallHistoryIntent.LoadMore)
        runCurrent()

        assertThat(viewModel.state.value.calls).containsExactly(first, second).inOrder()
        assertThat(viewModel.state.value.nextCursor).isNull()
    }

    @Test
    fun `formats history date and duration`() {
        val timestamp = java.time.Instant.parse("2026-07-12T13:45:00Z").toEpochMilli()

        assertThat(timestamp.toHistoryDate(java.time.ZoneId.of("UTC"))).isEqualTo("2026年7月12日 13:45")
        assertThat(3_723_000L.toHistoryDuration()).isEqualTo("1小时2分3秒")
        assertThat(125_000L.toHistoryDuration()).isEqualTo("2分5秒")
    }

    private fun history(callId: String, startedAt: Long) = CallHistoryEntry(
        callId = callId,
        startedAtEpochMillis = startedAt,
        durationMillis = 60_000L,
    )
}
