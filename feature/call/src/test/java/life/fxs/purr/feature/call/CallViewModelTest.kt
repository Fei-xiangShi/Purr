package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallRecordingStatus
import life.fxs.purr.domain.call.model.RecordingDownload
import life.fxs.purr.domain.call.usecase.ConnectCallUseCase
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import life.fxs.purr.domain.call.usecase.SelectAudioRouteUseCase
import life.fxs.purr.domain.call.usecase.ToggleMuteUseCase
import life.fxs.purr.domain.call.usecase.LoadCallRecordingsUseCase
import life.fxs.purr.domain.call.usecase.CreateRecordingDownloadUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessionFlow = MutableStateFlow<CallSession?>(null)

    private val prepareCallSessionUseCase = mockk<PrepareCallSessionUseCase>()
    private val connectCallUseCase = mockk<ConnectCallUseCase>()
    private val observeCallStateUseCase = mockk<ObserveCallStateUseCase>()
    private val toggleMuteUseCase = mockk<ToggleMuteUseCase>()
    private val selectAudioRouteUseCase = mockk<SelectAudioRouteUseCase>()
    private val disconnectCallUseCase = mockk<DisconnectCallUseCase>()
    private val loadCallRecordingsUseCase = mockk<LoadCallRecordingsUseCase>()
    private val createRecordingDownloadUseCase = mockk<CreateRecordingDownloadUseCase>()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { observeCallStateUseCase.invoke() } returns sessionFlow
        coEvery { connectCallUseCase.invoke() } returns AppResult.Success(Unit)
        coEvery { toggleMuteUseCase.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { selectAudioRouteUseCase.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { disconnectCallUseCase.invoke() } returns AppResult.Success(Unit)
        coEvery { loadCallRecordingsUseCase.invoke(any()) } returns AppResult.Success(emptyList())
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `connect waits for microphone permission before starting call`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        advanceUntilIdle()

        coVerify(exactly = 0) { prepareCallSessionUseCase.invoke(any()) }
        coVerify(exactly = 0) { connectCallUseCase.invoke() }
    }

    @Test
    fun `microphone permission grant starts call`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        viewModel.onIntent(CallIntent.RecordingConsentResult(granted = true))
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            prepareCallSessionUseCase.invoke(match { it.pairId == "pair-1" && it.recordingConsent })
        }
        coVerify(exactly = 1) { connectCallUseCase.invoke() }
    }

    @Test
    fun `declining recording consent prevents joining call`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        assertThat(viewModel.state.value.recordingConsentRequired).isTrue()
        viewModel.onIntent(CallIntent.RecordingConsentResult(granted = false))
        advanceUntilIdle()

        assertThat(viewModel.state.value.recordingConsentRequired).isFalse()
        coVerify(exactly = 0) { prepareCallSessionUseCase.invoke(any()) }
        coVerify(exactly = 0) { connectCallUseCase.invoke() }
    }

    @Test
    fun `state maps active session`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )

        advanceUntilIdle()

        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Active)
        assertThat(viewModel.state.value.activeRoute).isEqualTo(AudioRoute.Speaker)
        assertThat(viewModel.state.value.recordingState).isEqualTo(RecordingState.Recording)
        assertThat(viewModel.state.value.isForegroundServiceActive).isTrue()
    }

    @Test
    fun `state maps server reported transitional recording session`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Starting,
        )

        advanceUntilIdle()

        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Active)
        assertThat(viewModel.state.value.recordingState).isEqualTo(RecordingState.Starting)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `state maps reconnecting session`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Reconnecting,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.NotRecording,
        )

        advanceUntilIdle()

        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Reconnecting)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `ended call loads recordings and requests authorized playback`() = runTest(dispatcher) {
        val recording = CallRecording(
            recordingId = "recording-1",
            callId = "call-1",
            status = CallRecordingStatus.Available,
            downloadAvailable = true,
            startedAtEpochMillis = 1L,
            endedAtEpochMillis = 2_001L,
            durationMillis = 2_000L,
            sizeBytes = 1_024L,
            failureReason = null,
        )
        coEvery { loadCallRecordingsUseCase.invoke("call-1") } returns AppResult.Success(listOf(recording))
        coEvery { createRecordingDownloadUseCase.invoke("call-1", "recording-1") } returns AppResult.Success(
            RecordingDownload("recording-1", "https://storage.example/signed", 10_000L),
        )
        val viewModel = createViewModel()

        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Disconnected,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.recordings).containsExactly(recording)
        coVerify(exactly = 1) { loadCallRecordingsUseCase.invoke("call-1") }

        viewModel.onIntent(CallIntent.PlayRecording("recording-1"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.playbackLoadingRecordingId).isEqualTo("recording-1")
        coVerify(exactly = 1) { createRecordingDownloadUseCase.invoke("call-1", "recording-1") }

        viewModel.onIntent(CallIntent.RecordingPlaybackStarted("recording-1"))
        assertThat(viewModel.state.value.playbackLoadingRecordingId).isNull()
        assertThat(viewModel.state.value.playingRecordingId).isEqualTo("recording-1")
    }

    private fun createViewModel(): CallViewModel = CallViewModel(
        prepareCallSessionUseCase = prepareCallSessionUseCase,
        connectCallUseCase = connectCallUseCase,
        observeCallStateUseCase = observeCallStateUseCase,
        toggleMuteUseCase = toggleMuteUseCase,
        selectAudioRouteUseCase = selectAudioRouteUseCase,
        disconnectCallUseCase = disconnectCallUseCase,
        loadCallRecordingsUseCase = loadCallRecordingsUseCase,
        createRecordingDownloadUseCase = createRecordingDownloadUseCase,
    )

    private fun sampleSession(
        connectionState: CallConnectionState,
        localAudioState: LocalAudioState,
        recordingState: RecordingState,
    ) = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self", remote = "partner"),
        roomName = "room-1",
        wsUrl = "wss://example.invalid",
        token = "token",
        connectionState = connectionState,
        localAudioState = localAudioState,
        recordingState = recordingState,
        uiSnapshot = CallUiSnapshot(
            activeAudioRoute = AudioRoute.Speaker,
            availableAudioRoutes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
            isForegroundServiceActive = true,
        ),
    )
}
