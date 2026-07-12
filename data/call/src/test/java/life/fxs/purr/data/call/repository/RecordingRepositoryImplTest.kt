package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrRecordingApi
import life.fxs.purr.core.network.model.CallRecordingDto
import life.fxs.purr.core.network.model.CallRecordingsResponseDto
import life.fxs.purr.core.network.model.RecordingDownloadDto
import life.fxs.purr.core.network.model.RecordingLibraryResponseDto
import life.fxs.purr.domain.call.model.CallRecordingStatus
import org.junit.Test

class RecordingRepositoryImplTest {
    private val api = mockk<PurrRecordingApi>()
    private val repository = RecordingRepositoryImpl(api)

    @Test
    fun `recording operations delegate through the focused recording API`() = runTest {
        val dto = CallRecordingDto(
            recordingId = "recording-1",
            callId = "call-1",
            status = "stopped",
            downloadAvailable = true,
            startedAtEpochMillis = 1L,
            endedAtEpochMillis = 2_001L,
            durationMillis = 2_000L,
            sizeBytes = 1_024L,
        )
        coEvery { api.getRecordings("call-1") } returns CallRecordingsResponseDto(listOf(dto))
        coEvery { api.getRecordingLibrary(20, null) } returns RecordingLibraryResponseDto(
            recordings = listOf(dto),
            nextCursor = "next-page",
        )
        coEvery { api.createRecordingDownload("call-1", "recording-1") } returns RecordingDownloadDto(
            recordingId = "recording-1",
            url = "https://storage.example/recording.ogg?signature=test",
            expiresAtEpochMillis = 10_000L,
        )

        val callRecordings = repository.loadCallRecordings("call-1") as AppResult.Success
        assertThat(callRecordings.value.single().status).isEqualTo(CallRecordingStatus.Available)
        assertThat(callRecordings.value.single().durationMillis).isEqualTo(2_000L)

        val library = repository.loadRecordingLibrary(null) as AppResult.Success
        assertThat(library.value.nextCursor).isEqualTo("next-page")
        assertThat(library.value.recordings.single().callId).isEqualTo("call-1")

        val download = repository.createRecordingDownload("call-1", "recording-1") as AppResult.Success
        assertThat(download.value.url).isEqualTo("https://storage.example/recording.ogg?signature=test")

        coVerify(exactly = 1) { api.getRecordings("call-1") }
        coVerify(exactly = 1) { api.getRecordingLibrary(20, null) }
        coVerify(exactly = 1) { api.createRecordingDownload("call-1", "recording-1") }
    }
}
