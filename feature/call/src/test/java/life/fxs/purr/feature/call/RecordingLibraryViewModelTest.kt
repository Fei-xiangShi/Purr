package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallRecordingStatus
import life.fxs.purr.domain.call.model.RecordingDownload
import life.fxs.purr.domain.call.model.RecordingPage
import life.fxs.purr.domain.call.usecase.CreateRecordingDownloadUseCase
import life.fxs.purr.domain.call.usecase.LoadRecordingLibraryUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val loadLibrary = mockk<LoadRecordingLibraryUseCase>()
    private val createDownload = mockk<CreateRecordingDownloadUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `library survives session absence and appends cursor pages`() = runTest(dispatcher) {
        val first = recording("recording-2", "call-2")
        val second = recording("recording-1", "call-1")
        coEvery { loadLibrary.invoke(null) } returns AppResult.Success(
            RecordingPage(listOf(first), "next-page"),
        )
        coEvery { loadLibrary.invoke("next-page") } returns AppResult.Success(
            RecordingPage(listOf(second), null),
        )
        val viewModel = RecordingLibraryViewModel(loadLibrary, createDownload)

        advanceUntilIdle()
        assertThat(viewModel.state.value.recordings).containsExactly(first)
        assertThat(viewModel.state.value.nextCursor).isEqualTo("next-page")

        viewModel.onIntent(RecordingLibraryIntent.LoadMore)
        advanceUntilIdle()

        assertThat(viewModel.state.value.recordings).containsExactly(first, second).inOrder()
        assertThat(viewModel.state.value.nextCursor).isNull()
        coVerify(exactly = 1) { loadLibrary.invoke(null) }
        coVerify(exactly = 1) { loadLibrary.invoke("next-page") }
    }

    @Test
    fun `play requests a fresh URL using recording call id`() = runTest(dispatcher) {
        val recording = recording("recording-1", "call-1")
        coEvery { loadLibrary.invoke(null) } returns AppResult.Success(RecordingPage(listOf(recording), null))
        coEvery { createDownload.invoke("call-1", "recording-1") } returns AppResult.Success(
            RecordingDownload("recording-1", "https://storage.example/signed", 10_000L),
        )
        val viewModel = RecordingLibraryViewModel(loadLibrary, createDownload)
        advanceUntilIdle()

        viewModel.onIntent(RecordingLibraryIntent.Play("recording-1"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.playbackLoadingRecordingId).isEqualTo("recording-1")
        coVerify(exactly = 1) { createDownload.invoke("call-1", "recording-1") }
    }

    private fun recording(recordingId: String, callId: String) = CallRecording(
        recordingId = recordingId,
        callId = callId,
        status = CallRecordingStatus.Available,
        downloadAvailable = true,
        startedAtEpochMillis = 1L,
        endedAtEpochMillis = 2L,
        durationMillis = 1L,
        sizeBytes = 10L,
        failureReason = null,
    )
}
