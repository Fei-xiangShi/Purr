package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallHistoryApi
import life.fxs.purr.core.network.model.CallCalendarDayDto
import life.fxs.purr.core.network.model.CallCalendarResponseDto
import life.fxs.purr.core.network.model.CallDetailDto
import life.fxs.purr.core.network.model.CallHistoryItemDto
import life.fxs.purr.core.network.model.CallHistoryResponseDto
import life.fxs.purr.core.network.model.CallQualitySummaryDto
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallOutcome
import org.junit.Test

class ApiCallHistoryRepositoryTest {
    private val api = mockk<PurrCallHistoryApi>()

    @Test
    fun `maps a paged day response`() = runTest {
        coEvery { api.getDay(1_000L, 2_000L, 50, "cursor-1") } returns CallHistoryResponseDto(
            calls = listOf(historyDto()),
            nextCursor = "cursor-2",
        )

        val result = ApiCallHistoryRepository(api).loadDay(1_000L, 2_000L, "cursor-1") as AppResult.Success

        assertThat(result.value.calls.single().direction).isEqualTo(CallDirection.Incoming)
        assertThat(result.value.calls.single().outcome).isEqualTo(CallOutcome.Completed)
        assertThat(result.value.nextCursor).isEqualTo("cursor-2")
        coVerify(exactly = 1) { api.getDay(1_000L, 2_000L, 50, "cursor-1") }
    }

    @Test
    fun `calendar and detail adapters map their focused projections`() = runTest {
        coEvery { api.getCalendar(1L, 2L, "UTC") } returns CallCalendarResponseDto(
            listOf(CallCalendarDayDto("2026-07-12", 3, 90_000L)),
        )
        coEvery { api.getDetail("call-1") } returns CallDetailDto(
            callId = "call-1",
            direction = "outgoing",
            outcome = "completed",
            requestedAtEpochMillis = 1_000L,
            connectedAtEpochMillis = 2_000L,
            endedAtEpochMillis = 62_000L,
            ringingDurationMillis = 1_000L,
            durationMillis = 60_000L,
            recordingStatus = "stopped",
            recordingCount = 1,
            recordingAvailable = true,
            quality = CallQualitySummaryDto(sampleCount = 2, averageRoundTripTimeMs = 42.0),
        )

        val calendar = ApiCallCalendarRepository(api).loadMonth(1L, 2L, "UTC") as AppResult.Success
        val detail = ApiCallDetailRepository(api).loadDetail("call-1") as AppResult.Success

        assertThat(calendar.value.single().callCount).isEqualTo(3)
        assertThat(detail.value.quality?.averageRoundTripTimeMs).isEqualTo(42.0)
        assertThat(detail.value.recordingAvailable).isTrue()
    }

    private fun historyDto() = CallHistoryItemDto(
        callId = "call-1",
        direction = "incoming",
        outcome = "completed",
        requestedAtEpochMillis = 500L,
        startedAtEpochMillis = 1_000L,
        connectedAtEpochMillis = 1_000L,
        endedAtEpochMillis = 66_000L,
        ringingDurationMillis = 500L,
        durationMillis = 65_000L,
        recordingStatus = "stopped",
    )
}
