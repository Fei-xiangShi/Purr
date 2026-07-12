package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallHistoryItemDto
import life.fxs.purr.core.network.model.CallHistoryResponseDto
import org.junit.Test

class ApiCallHistoryRepositoryTest {
    private val api = mockk<PurrCallApi>()
    private val repository = ApiCallHistoryRepository(api)

    @Test
    fun `maps a paged call history response`() = runTest {
        coEvery { api.getCallHistory(20, "cursor-1") } returns CallHistoryResponseDto(
            calls = listOf(
                CallHistoryItemDto(
                    callId = "call-1",
                    startedAtEpochMillis = 1_000L,
                    durationMillis = 65_000L,
                ),
            ),
            nextCursor = "cursor-2",
        )

        val result = repository.loadHistory("cursor-1") as AppResult.Success

        assertThat(result.value.calls.single().startedAtEpochMillis).isEqualTo(1_000L)
        assertThat(result.value.calls.single().durationMillis).isEqualTo(65_000L)
        assertThat(result.value.nextCursor).isEqualTo("cursor-2")
        coVerify(exactly = 1) { api.getCallHistory(20, "cursor-1") }
    }
}
