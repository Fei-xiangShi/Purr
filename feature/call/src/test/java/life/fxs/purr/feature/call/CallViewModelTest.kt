package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `connect creates call session before requesting microphone permission`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            prepareCallSessionUseCase.invoke(match { it.pairId == "pair-1" && it.recordingConsent })
        }
        coVerify(exactly = 0) { connectCallUseCase.invoke() }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Dialing)
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
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            prepareCallSessionUseCase.invoke(match { it.pairId == "pair-1" && it.recordingConsent })
        }
        coVerify(exactly = 1) { connectCallUseCase.invoke() }
    }

    @Test
    fun `microphone permission denial prevents joining call`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = false))
        advanceUntilIdle()

        coVerify(exactly = 1) { prepareCallSessionUseCase.invoke(any()) }
        coVerify(exactly = 0) { connectCallUseCase.invoke() }
        coVerify(exactly = 1) { disconnectCallUseCase.invoke() }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
    }

    @Test
    fun `ending during preparation waits for session then disconnects without connecting`() = runTest(dispatcher) {
        val preparedSession = CompletableDeferred<AppResult<CallSession>>()
        coEvery { prepareCallSessionUseCase.invoke(any()) } coAnswers { preparedSession.await() }
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        runCurrent()
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Dialing)

        viewModel.onIntent(CallIntent.EndCall)
        runCurrent()
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ending)

        preparedSession.complete(AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        )))
        advanceUntilIdle()

        coVerify(exactly = 0) { connectCallUseCase.invoke() }
        coVerify(exactly = 1) { disconnectCallUseCase.invoke() }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `ending while connecting cancels connection before disconnecting`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        coEvery { connectCallUseCase.invoke() } coAnswers { awaitCancellation() }
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.ConnectCall(pairId = "pair-1"))
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        runCurrent()
        viewModel.onIntent(CallIntent.EndCall)
        advanceUntilIdle()

        coVerify(exactly = 1) { connectCallUseCase.invoke() }
        coVerify(exactly = 1) { disconnectCallUseCase.invoke() }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
        assertThat(viewModel.state.value.isLoading).isFalse()
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
        assertThat(viewModel.state.value.isForegroundServiceActive).isTrue()
    }

    @Test
    fun `connected local participant waits until remote participant joins`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.NotRecording,
            remoteParticipantConnected = false,
        )

        advanceUntilIdle()

        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Waiting)
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
        remoteParticipantConnected: Boolean = true,
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
            remoteParticipantConnected = remoteParticipantConnected,
        ),
    )
}
