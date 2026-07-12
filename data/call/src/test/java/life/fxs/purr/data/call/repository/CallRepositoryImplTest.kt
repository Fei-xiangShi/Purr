package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioFocusManager
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.core.network.model.SessionResponseDto
import life.fxs.purr.core.network.model.CallRecordingDto
import life.fxs.purr.core.network.model.CallRecordingsResponseDto
import life.fxs.purr.core.network.model.RecordingDownloadDto
import life.fxs.purr.core.network.model.RecordingLibraryResponseDto
import life.fxs.purr.data.call.livekit.LiveKitCallDataSource
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.model.CallRecordingStatus
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryImplTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<PurrCallApi>()
    private val liveKitCallDataSource = mockk<LiveKitCallDataSource>()
    private val audioRouteController = mockk<AudioRouteController>()
    private val callAudioFocusManager = mockk<CallAudioFocusManager>()
    private val callServiceController = mockk<CallServiceController>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `status sync disconnects local call when server marks call ended`() = runTest(dispatcher) {
        val foreground = MutableStateFlow(true)
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.isCallForeground } returns foreground
        every { liveKitCallDataSource.sessionEvents } returns emptyFlow()
        every { liveKitCallDataSource.updateSession(any()) } returns Unit
        coEvery { liveKitCallDataSource.disconnect() } returns Unit
        coEvery { callServiceController.stopForegroundCall() } coAnswers {
            foreground.emit(false)
        }
        coEvery { callAudioFocusManager.abandonFocus() } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            callId = "call-1",
            pairId = "pair-1",
            roomName = "room-1",
            participantIdentity = "self",
            token = "token",
            wsUrl = "ws://example.invalid",
        )
        coEvery { api.getCall("call-1") } returnsMany listOf(
            CallStatusDto(
                callId = "call-1",
                pairId = "pair-1",
                state = "idle",
                recordingStatus = "idle",
                startedAtEpochMillis = 1L,
            ),
            CallStatusDto(
                callId = "call-1",
                pairId = "pair-1",
                state = "ended",
                recordingStatus = "stopped",
                startedAtEpochMillis = 1L,
                endedAtEpochMillis = 2L,
            ),
        )
        coEvery { api.getRecordings("call-1") } returns CallRecordingsResponseDto(
            recordings = listOf(
                CallRecordingDto(
                    recordingId = "recording-1",
                    callId = "call-1",
                    status = "stopped",
                    downloadAvailable = true,
                    startedAtEpochMillis = 1L,
                    endedAtEpochMillis = 2_001L,
                    durationMillis = 2_000L,
                    sizeBytes = 1_024L,
                ),
            ),
        )
        coEvery { api.createRecordingDownload("call-1", "recording-1") } returns RecordingDownloadDto(
            recordingId = "recording-1",
            url = "https://storage.example/recording.ogg?signature=test",
            expiresAtEpochMillis = 10_000L,
        )
        coEvery { api.getRecordingLibrary(20, null) } returns RecordingLibraryResponseDto(
            recordings = listOf(
                CallRecordingDto(
                    recordingId = "recording-1",
                    callId = "call-1",
                    status = "stopped",
                    downloadAvailable = true,
                    durationMillis = 2_000L,
                ),
            ),
            nextCursor = "next-page",
        )

        val repository = CallRepositoryImpl(
            api = api,
            liveKitCallDataSource = liveKitCallDataSource,
            audioRouteController = audioRouteController,
            callAudioFocusManager = callAudioFocusManager,
            callServiceController = callServiceController,
        )

        val result = repository.prepareCall(PrepareCallParams(pairId = "pair-1", recordingConsent = true))
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        advanceUntilIdle()

        val session = repository.observeCallSession().first { it?.connectionState == CallConnectionState.Disconnected }
        assertThat(session?.connectionState).isEqualTo(CallConnectionState.Disconnected)
        assertThat(session?.localAudioState).isEqualTo(LocalAudioState.Disabled)
        assertThat(session?.recordingState).isEqualTo(RecordingState.NotRecording)
        assertThat(session?.uiSnapshot?.remoteParticipantConnected).isEqualTo(false)
        assertThat(session?.uiSnapshot?.isForegroundServiceActive).isEqualTo(false)

        val recordings = repository.loadRecordings()
        assertThat(recordings).isInstanceOf(AppResult.Success::class.java)
        val recording = (recordings as AppResult.Success).value.single()
        assertThat(recording.status).isEqualTo(CallRecordingStatus.Available)
        assertThat(recording.downloadAvailable).isTrue()
        assertThat(recording.durationMillis).isEqualTo(2_000L)

        val download = repository.createRecordingDownload("call-1", "recording-1")
        assertThat(download).isInstanceOf(AppResult.Success::class.java)
        assertThat((download as AppResult.Success).value.url)
            .isEqualTo("https://storage.example/recording.ogg?signature=test")

        val library = repository.loadRecordingLibrary(null)
        assertThat(library).isInstanceOf(AppResult.Success::class.java)
        val libraryValue = (library as AppResult.Success).value
        assertThat(libraryValue.nextCursor).isEqualTo("next-page")
        assertThat(libraryValue.recordings.single().callId).isEqualTo("call-1")

        coVerify(exactly = 1) { liveKitCallDataSource.disconnect() }
        coVerify(exactly = 1) { callServiceController.stopForegroundCall() }
        coVerify(exactly = 1) { callAudioFocusManager.abandonFocus() }
        coVerify(exactly = 1) { api.getRecordings("call-1") }
        coVerify(exactly = 1) { api.createRecordingDownload("call-1", "recording-1") }
        coVerify(exactly = 1) { api.getRecordingLibrary(20, null) }
    }
}
