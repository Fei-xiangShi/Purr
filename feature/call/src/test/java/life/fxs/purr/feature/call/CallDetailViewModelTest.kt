package life.fxs.purr.feature.call

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
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
import life.fxs.purr.domain.call.model.CallDetail
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallOutcome
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallRecordingStatus
import life.fxs.purr.domain.call.model.CallTranscript
import life.fxs.purr.domain.call.model.CallTranscriptStatus
import life.fxs.purr.domain.call.model.RecordingDownload
import life.fxs.purr.domain.call.usecase.CreateRecordingDownloadUseCase
import life.fxs.purr.domain.call.usecase.LoadCallDetailUseCase
import life.fxs.purr.domain.call.usecase.LoadCallRecordingsUseCase
import life.fxs.purr.domain.call.usecase.LoadCallTranscriptUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val loadDetail = mockk<LoadCallDetailUseCase>()
    private val loadRecordings = mockk<LoadCallRecordingsUseCase>()
    private val loadTranscript = mockk<LoadCallTranscriptUseCase>()
    private val createDownload = mockk<CreateRecordingDownloadUseCase>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads independent detail projections and emits a sanitized download request`() = runTest(dispatcher) {
        coEvery { loadDetail("call-1") } returns AppResult.Success(detail())
        coEvery { loadRecordings("call-1") } returns AppResult.Success(listOf(recording()))
        coEvery { loadTranscript("call-1") } returns AppResult.Success(
            CallTranscript(CallTranscriptStatus.Unavailable),
        )
        coEvery { createDownload("call-1", "recording/1") } returns AppResult.Success(
            RecordingDownload("recording/1", "https://storage.example/signed", 10_000L),
        )
        val viewModel = CallDetailViewModel(
            SavedStateHandle(mapOf(CALL_HISTORY_CALL_ID_ARG to "call-1")),
            loadDetail,
            loadRecordings,
            loadTranscript,
            createDownload,
        )
        runCurrent()

        assertThat(viewModel.state.value.detail?.callId).isEqualTo("call-1")
        assertThat(viewModel.state.value.recordings).hasSize(1)
        assertThat(viewModel.state.value.transcript?.status).isEqualTo(CallTranscriptStatus.Unavailable)

        viewModel.effects.test {
            viewModel.onIntent(CallDetailIntent.DownloadRecording("recording/1"))
            runCurrent()

            val effect = awaitItem() as CallDetailEffect.DownloadReady
            assertThat(effect.url).isEqualTo("https://storage.example/signed")
            assertThat(effect.fileName).doesNotContain("/")
        }
    }

    private fun detail() = CallDetail(
        callId = "call-1",
        direction = CallDirection.Outgoing,
        outcome = CallOutcome.Completed,
        requestedAtEpochMillis = 1_000L,
        connectedAtEpochMillis = 2_000L,
        endedAtEpochMillis = 62_000L,
        ringingDurationMillis = 1_000L,
        durationMillis = 60_000L,
        recordingStatus = "stopped",
        recordingCount = 1,
        recordingAvailable = true,
        quality = null,
    )

    private fun recording() = CallRecording(
        recordingId = "recording/1",
        callId = "call-1",
        status = CallRecordingStatus.Available,
        downloadAvailable = true,
        startedAtEpochMillis = 2_000L,
        endedAtEpochMillis = 62_000L,
        durationMillis = 60_000L,
        sizeBytes = 1_024L,
        failureReason = null,
    )
}
