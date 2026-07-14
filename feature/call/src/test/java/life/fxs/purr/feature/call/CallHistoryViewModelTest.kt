package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallCalendarDay
import life.fxs.purr.domain.call.usecase.LoadCallCalendarUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val loadCalendar = mockk<LoadCallCalendarUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps calendar response by date and navigates months`() = runTest(dispatcher) {
        val calendarDay = CallCalendarDay("2026-07-12", 2, 120_000L)
        coEvery { loadCalendar(any(), any(), any()) } returns AppResult.Success(listOf(calendarDay))
        val viewModel = CallHistoryViewModel(loadCalendar)
        val initialMonth = viewModel.state.value.displayedMonth
        runCurrent()

        assertThat(viewModel.state.value.days[LocalDate.parse("2026-07-12")]).isEqualTo(calendarDay)

        viewModel.onIntent(CallHistoryIntent.PreviousMonth)
        runCurrent()

        assertThat(viewModel.state.value.displayedMonth).isEqualTo(initialMonth.minusMonths(1))
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `formats history date and duration`() {
        val timestamp = java.time.Instant.parse("2026-07-12T13:45:00Z").toEpochMilli()

        assertThat(timestamp.toHistoryDate(java.time.ZoneId.of("UTC"))).isEqualTo("2026年7月12日 13:45")
        assertThat(3_723_000L.toHistoryDuration()).isEqualTo("1小时2分3秒")
        assertThat(125_000L.toHistoryDuration()).isEqualTo("2分5秒")
    }
}
