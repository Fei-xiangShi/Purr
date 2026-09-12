package life.fxs.purr.feature.call

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
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
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.media.screenshare.ScreenShareQuality
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherController
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherStatus
import life.fxs.purr.core.media.screenshare.WhepPlaybackController
import life.fxs.purr.core.media.screenshare.WhepPlaybackRequest
import life.fxs.purr.core.media.screenshare.WhepPlaybackStatus
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallInterruptionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.LocalCallInterruption
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.model.RemoteCallInterruption
import life.fxs.purr.domain.call.model.ScreenShareMediaEndpoint
import life.fxs.purr.domain.call.model.ScreenSharePublishing
import life.fxs.purr.domain.call.model.ScreenShareSession
import life.fxs.purr.domain.call.model.ScreenShareSnapshot
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.model.ScreenShareStatus
import life.fxs.purr.domain.call.usecase.CreateScreenShareUseCase
import life.fxs.purr.domain.call.usecase.ConnectCallUseCase
import life.fxs.purr.domain.call.usecase.CancelCallPreparationUseCase
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.ObserveScreenShareUseCase
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import life.fxs.purr.domain.call.usecase.SelectAudioRouteUseCase
import life.fxs.purr.domain.call.usecase.StopScreenShareUseCase
import life.fxs.purr.domain.call.usecase.ToggleMuteUseCase
import life.fxs.purr.domain.incomingcall.PrepareIncomingCallUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessionFlow = MutableStateFlow<CallSession?>(null)

    private val prepareCallSessionUseCase = mockk<PrepareCallSessionUseCase>()
    private val prepareIncomingCallUseCase = mockk<PrepareIncomingCallUseCase>()
    private val cancelCallPreparationUseCase = mockk<CancelCallPreparationUseCase>()
    private val connectCallUseCase = mockk<ConnectCallUseCase>()
    private val observeCallStateUseCase = mockk<ObserveCallStateUseCase>()
    private val toggleMuteUseCase = mockk<ToggleMuteUseCase>()
    private val selectAudioRouteUseCase = mockk<SelectAudioRouteUseCase>()
    private val disconnectCallUseCase = mockk<DisconnectCallUseCase>()
    private val observeScreenShareUseCase = mockk<ObserveScreenShareUseCase>()
    private val createScreenShareUseCase = mockk<CreateScreenShareUseCase>()
    private val stopScreenShareUseCase = mockk<StopScreenShareUseCase>()
    private val screenSharePublisherController = mockk<ScreenSharePublisherController>()
    private val whepPlaybackController = mockk<WhepPlaybackController>()
    private val screenShareFlow = MutableStateFlow(ScreenShareSnapshot(callId = "call-1"))
    private val publisherStatusFlow = MutableStateFlow<ScreenSharePublisherStatus>(
        ScreenSharePublisherStatus.Idle,
    )
    private val whepStatusFlow = MutableStateFlow<WhepPlaybackStatus>(WhepPlaybackStatus.Idle)
    private val audioLevelProvider = object : CallAudioLevelProvider {
        override val localAudioLevel = MutableStateFlow(0f)
        override val remoteAudioLevel = MutableStateFlow(0f)
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { observeCallStateUseCase.invoke() } returns sessionFlow
        coEvery { connectCallUseCase.invoke() } returns AppResult.Success(Unit)
        coEvery { toggleMuteUseCase.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { selectAudioRouteUseCase.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { disconnectCallUseCase.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { cancelCallPreparationUseCase.invoke() } returns Unit
        every { observeScreenShareUseCase.invoke(any()) } returns screenShareFlow
        coEvery { stopScreenShareUseCase.invoke(any()) } returns AppResult.Success(null)
        every { screenSharePublisherController.status } returns publisherStatusFlow
        every { screenSharePublisherController.prepare(any()) } just Runs
        every { screenSharePublisherController.start(any(), any()) } just Runs
        every { screenSharePublisherController.permissionDenied(any()) } just Runs
        every { screenSharePublisherController.stop(any()) } just Runs
        every { whepPlaybackController.status } returns whepStatusFlow
        every { whepPlaybackController.start(any()) } just Runs
        every { whepPlaybackController.stop(any()) } just Runs
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `late share creation after hangup is compensated without asking permission`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.NotRecording,
        )
        val result = CompletableDeferred<AppResult<ScreenShareSession>>()
        coEvery { createScreenShareUseCase.invoke("call-1", ScreenShareSource.Mobile) } coAnswers { result.await() }
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall("pair-1", "call-1", direction = CallDirection.Outgoing))
        runCurrent()
        viewModel.onIntent(CallIntent.StartMobileScreenShare)
        viewModel.onIntent(CallIntent.StartMobileScreenShare)
        runCurrent()
        viewModel.onIntent(CallIntent.EndCall)
        result.complete(AppResult.Success(sampleScreenShare(ScreenShareSource.Mobile)))
        runCurrent()

        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
        assertThat(viewModel.state.value.screenShare.session).isNull()
        assertThat(viewModel.state.value.screenShare.obsSetupVisible).isFalse()
        verify(exactly = 0) { screenSharePublisherController.prepare(any()) }
        coVerify(exactly = 1) { createScreenShareUseCase.invoke(any(), any()) }
        coVerify(exactly = 2) { stopScreenShareUseCase.invoke("call-1") }
    }

    @Test
    fun `stale playback stop cannot hide current portrait stream and refresh preserves playback failure`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.NotRecording,
        )
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall("pair-1", "call-1", direction = CallDirection.Outgoing))
        runCurrent()
        val endpoint = sampleMediaEndpoint("https://media.test/whep")
        val share = sampleScreenShare(ScreenShareSource.Mobile, ScreenShareStatus.Live, playback = endpoint)
        screenShareFlow.value = ScreenShareSnapshot("call-1", session = share, isOwnedByCurrentUser = false)
        runCurrent()
        val request = WhepPlaybackRequest("call-1", share.shareId, endpoint.url, endpoint.bearerToken, endpoint.expiresAtEpochMillis)
        whepStatusFlow.value = WhepPlaybackStatus.Live(request, 720, 1280)
        runCurrent()
        whepStatusFlow.value = WhepPlaybackStatus.Stopped("old-share")
        runCurrent()
        assertThat(viewModel.state.value.screenShare.remoteState).isEqualTo(RemoteScreenShareUiState.Live)
        assertThat(viewModel.state.value.screenShare.remoteAspectRatio).isEqualTo(720f / 1280)

        whepStatusFlow.value = WhepPlaybackStatus.Failed(request, "ICE failed")
        runCurrent()
        screenShareFlow.value = screenShareFlow.value.copy(syncErrorMessage = "refresh failed")
        runCurrent()
        assertThat(viewModel.state.value.screenShare.remoteState).isEqualTo(RemoteScreenShareUiState.Failed)
    }

    @Test
    fun `connect creates call session before requesting microphone permission`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.StartNewOutgoingCall(pairId = "pair-1"))
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

        viewModel.onIntent(CallIntent.StartNewOutgoingCall(pairId = "pair-1"))
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            prepareCallSessionUseCase.invoke(match { it.pairId == "pair-1" && it.recordingConsent })
        }
        coVerify(exactly = 1) { connectCallUseCase.invoke() }
    }

    @Test
    fun `incoming navigation direction is preserved in call preparation`() = runTest(dispatcher) {
        coEvery { prepareIncomingCallUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                remoteDisplayName = "Partner",
                direction = CallDirection.Incoming,
                callId = "call-1",
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            prepareIncomingCallUseCase.invoke(
                match { params ->
                    params.pairId == "pair-1" &&
                        params.remoteDisplayName == "Partner" &&
                        params.direction == CallDirection.Incoming &&
                        params.callId == "call-1"
                },
            )
        }
        coVerify(exactly = 0) { prepareCallSessionUseCase.invoke(any()) }
    }

    @Test
    fun `reentering an identified active call attaches without preparing or connecting again`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-1", direction = CallDirection.Outgoing))
        advanceUntilIdle()

        coVerify(exactly = 0) { prepareCallSessionUseCase.invoke(any()) }
        coVerify(exactly = 0) { prepareIncomingCallUseCase.invoke(any()) }
        coVerify(exactly = 0) { connectCallUseCase.invoke() }
        assertThat(viewModel.state.value.session?.callId).isEqualTo("call-1")
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Active)
    }

    @Test
    fun `reentering a prepared call resumes at the permission boundary`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        )
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-1", direction = CallDirection.Outgoing))
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        advanceUntilIdle()

        coVerify(exactly = 0) { prepareCallSessionUseCase.invoke(any()) }
        coVerify(exactly = 0) { prepareIncomingCallUseCase.invoke(any()) }
        coVerify(exactly = 1) { connectCallUseCase.invoke() }
    }

    @Test
    fun `stale terminal snapshot cannot close a new call attempt`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            callId = "call-old",
            connectionState = CallConnectionState.Disconnected,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        )
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(
            sampleSession(
                callId = "call-new",
                connectionState = CallConnectionState.Preparing,
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            ),
        )
        val viewModel = createViewModel()

        viewModel.effects.test {
            viewModel.onIntent(CallIntent.StartNewOutgoingCall(pairId = "pair-1"))
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(CallEffect.RequestMicrophonePermission)
            assertThat(viewModel.state.value.session?.callId).isEqualTo("call-new")
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Dialing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `same call id terminal snapshot is ignored until a new preparation starts`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            callId = "call-1",
            connectionState = CallConnectionState.Disconnected,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        )
        coEvery { prepareIncomingCallUseCase.invoke(any()) } returns AppResult.Success(
            sampleSession(
                callId = "call-1",
                connectionState = CallConnectionState.Preparing,
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            ),
        )
        val viewModel = createViewModel()

        viewModel.effects.test {
            viewModel.onIntent(
                CallIntent.OpenExistingCall(
                    pairId = "pair-1",
                    callId = "call-1",
                    direction = CallDirection.Incoming,
                ),
            )
            runCurrent()

            assertThat(awaitItem()).isEqualTo(CallEffect.RequestMicrophonePermission)
            assertThat(viewModel.state.value.session?.connectionState)
                .isEqualTo(CallConnectionState.Preparing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `microphone permission denial prevents joining call`() = runTest(dispatcher) {
        coEvery { prepareCallSessionUseCase.invoke(any()) } returns AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        ))
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.StartNewOutgoingCall(pairId = "pair-1"))
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = false))
        advanceUntilIdle()

        coVerify(exactly = 1) { prepareCallSessionUseCase.invoke(any()) }
        coVerify(exactly = 0) { connectCallUseCase.invoke() }
        coVerify(exactly = 1) { disconnectCallUseCase.invoke("call-1") }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
    }

    @Test
    fun `ending during preparation ends immediately and disconnects without connecting`() = runTest(dispatcher) {
        val preparedSession = CompletableDeferred<AppResult<CallSession>>()
        coEvery { prepareCallSessionUseCase.invoke(any()) } coAnswers { preparedSession.await() }
        val viewModel = createViewModel()

        viewModel.onIntent(CallIntent.StartNewOutgoingCall(pairId = "pair-1"))
        runCurrent()
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Dialing)

        viewModel.onIntent(CallIntent.EndCall)
        runCurrent()
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)

        preparedSession.complete(AppResult.Success(sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Disabled,
            recordingState = RecordingState.NotRecording,
        )))
        advanceUntilIdle()

        coVerify(exactly = 0) { connectCallUseCase.invoke() }
        coVerify(exactly = 0) { disconnectCallUseCase.invoke(any()) }
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

        viewModel.onIntent(CallIntent.StartNewOutgoingCall(pairId = "pair-1"))
        runCurrent()
        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        runCurrent()
        viewModel.onIntent(CallIntent.EndCall)
        advanceUntilIdle()

        coVerify(exactly = 1) { connectCallUseCase.invoke() }
        coVerify(exactly = 1) { disconnectCallUseCase.invoke("call-1") }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `explicit termination navigates immediately and ignores old room updates`() = runTest(dispatcher) {
        val finishTermination = CompletableDeferred<Unit>()
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        coEvery { disconnectCallUseCase.invoke(any()) } coAnswers {
            finishTermination.await()
            AppResult.Success(Unit)
        }
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-1", direction = CallDirection.Outgoing))
        runCurrent()

        viewModel.effects.test {
            viewModel.onIntent(CallIntent.EndCall)

            assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
            assertThat(viewModel.state.value.isLoading).isFalse()

            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Reconnecting,
                localAudioState = LocalAudioState.Enabled,
                recordingState = RecordingState.Recording,
            )
            runCurrent()
            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Disconnected,
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            )
            runCurrent()

            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
            expectNoEvents()

            finishTermination.complete(Unit)
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnect failure cannot revive a locally ended call`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        coEvery { disconnectCallUseCase.invoke("call-1") } returns
            AppResult.Failure(AppError.Network("disconnect failed"))
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-1", direction = CallDirection.Outgoing))
        runCurrent()

        viewModel.effects.test {
            viewModel.onIntent(CallIntent.EndCall)

            assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)

            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Connected,
                localAudioState = LocalAudioState.Enabled,
                recordingState = RecordingState.Recording,
            )
            runCurrent()

            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `new call id starts after local end while old room updates stay ignored`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            callId = "call-old",
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        coEvery { prepareIncomingCallUseCase.invoke(any()) } returns AppResult.Success(
            sampleSession(
                callId = "call-new",
                connectionState = CallConnectionState.Preparing,
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            ),
        )
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-old", direction = CallDirection.Outgoing))
        runCurrent()

        viewModel.effects.test {
            viewModel.onIntent(CallIntent.EndCall)
            assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)

            viewModel.onIntent(
                CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-new", direction = CallDirection.Incoming),
            )
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(CallEffect.RequestMicrophonePermission)
            assertThat(viewModel.state.value.session?.callId).isEqualTo("call-new")

            sessionFlow.value = sampleSession(
                callId = "call-old",
                connectionState = CallConnectionState.Reconnecting,
                localAudioState = LocalAudioState.Enabled,
                recordingState = RecordingState.Recording,
            )
            runCurrent()

            assertThat(viewModel.state.value.session?.callId).isEqualTo("call-new")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server terminal state navigates the current call home once`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-1", direction = CallDirection.Outgoing))
        runCurrent()

        viewModel.effects.test {
            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Disconnected,
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            )
            runCurrent()

            assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `incoming preparation failure uses deterministic message and exits explicitly`() =
        runTest(dispatcher) {
            coEvery { prepareIncomingCallUseCase.invoke(any()) } returns
                AppResult.Failure(AppError.Unexpected(IllegalStateException()))
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onIntent(
                    CallIntent.OpenExistingCall(
                        pairId = "pair-1",
                        direction = CallDirection.Incoming,
                        callId = "call-1",
                    ),
                )
                runCurrent()

                assertThat(awaitItem()).isEqualTo(
                    CallEffect.ShowMessage("通话准备失败，请稍后重试"),
                )
                assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Failed)
                assertThat(viewModel.state.value.failureMessage)
                    .isEqualTo("通话准备失败，请稍后重试")
                expectNoEvents()

                viewModel.onIntent(CallIntent.EndCall)
                assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
                expectNoEvents()
                coVerify(exactly = 1) { disconnectCallUseCase.invoke("call-1") }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connection failure stays visible instead of navigating home`() = runTest(dispatcher) {
        coEvery { prepareIncomingCallUseCase.invoke(any()) } returns AppResult.Success(
            sampleSession(
                connectionState = CallConnectionState.Preparing,
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            ),
        )
        coEvery { connectCallUseCase.invoke() } returns
            AppResult.Failure(AppError.Network("Unable to parse TLS packet header"))
        val viewModel = createViewModel()

        viewModel.effects.test {
            viewModel.onIntent(
                CallIntent.OpenExistingCall(
                    pairId = "pair-1",
                    direction = CallDirection.Incoming,
                    callId = "call-1",
                ),
            )
            runCurrent()
            assertThat(awaitItem()).isEqualTo(CallEffect.RequestMicrophonePermission)

            viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(
                CallEffect.ShowMessage("网络连接失败，请检查网络后重试"),
            )
            expectNoEvents()
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Failed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repository failed state does not auto navigate`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val viewModel = createViewModel()
        viewModel.onIntent(CallIntent.OpenExistingCall(pairId = "pair-1", callId = "call-1", direction = CallDirection.Outgoing))
        runCurrent()

        viewModel.effects.test {
            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Failed(),
                localAudioState = LocalAudioState.Disabled,
                recordingState = RecordingState.NotRecording,
            )
            runCurrent()

            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Failed)
            assertThat(viewModel.state.value.failureMessage)
                .isEqualTo("通话连接失败，请稍后重试")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
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
    fun `route selection remains available while microphone is muted`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Muted,
            recordingState = RecordingState.Recording,
        )
        advanceUntilIdle()

        viewModel.onIntent(CallIntent.RouteSelect(AudioRoute.Earpiece))
        advanceUntilIdle()

        coVerify(exactly = 1) { selectAudioRouteUseCase.invoke(AudioRoute.Earpiece) }
        coVerify(exactly = 0) { toggleMuteUseCase.invoke(any()) }
        assertThat(viewModel.state.value.availableRoutes)
            .containsExactly(AudioRoute.Earpiece, AudioRoute.Speaker)
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
    fun `interruption projection uses local then remote precedence while termination wins`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Reconnecting,
                localAudioState = LocalAudioState.Enabled,
                recordingState = RecordingState.Recording,
                interruptionState = CallInterruptionState(
                    remote = RemoteCallInterruption.Suspended("remote-operation", degraded = false),
                ),
            )
            runCurrent()
            assertThat(viewModel.state.value.screenState)
                .isEqualTo(CallScreenState.RemoteSystemCallSuspended)

            sessionFlow.value = requireNotNull(sessionFlow.value).copy(
                interruptionState = CallInterruptionState(
                    local = LocalCallInterruption.Suspended("local-operation", degraded = false),
                    remote = RemoteCallInterruption.Suspended("remote-operation", degraded = false),
                ),
            )
            runCurrent()
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.SystemCallSuspended)

            sessionFlow.value = requireNotNull(sessionFlow.value).copy(
                interruptionState = CallInterruptionState(
                    local = LocalCallInterruption.Resuming("local-operation", 1, 20),
                ),
            )
            runCurrent()
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.ResumingAfterSystemCall)

            sessionFlow.value = requireNotNull(sessionFlow.value).copy(
                connectionState = CallConnectionState.Terminating,
            )
            runCurrent()
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ending)
        }

    @Test
    fun `mobile screen share requests projection permission before publishing`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val share = sampleScreenShare(source = ScreenShareSource.Mobile)
        coEvery {
            createScreenShareUseCase.invoke("call-1", ScreenShareSource.Mobile)
        } returns AppResult.Success(share)
        val viewModel = createViewModel()
        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                callId = "call-1",
                direction = CallDirection.Outgoing,
            ),
        )
        runCurrent()

        viewModel.effects.test {
            viewModel.onIntent(CallIntent.SelectPublishQuality(ScreenShareQuality.QHD60))
            viewModel.onIntent(CallIntent.StartMobileScreenShare)
            advanceUntilIdle()

            val effect = awaitItem() as CallEffect.RequestScreenCapturePermission
            assertThat(effect.request.shareId).isEqualTo("share-1")
            assertThat(effect.request.quality).isEqualTo(ScreenShareQuality.QHD60)
            viewModel.onIntent(CallIntent.SelectPublishQuality(ScreenShareQuality.HD30))
            assertThat(viewModel.state.value.screenShare.publishQuality).isEqualTo(ScreenShareQuality.QHD60)
            verify(exactly = 1) {
                screenSharePublisherController.prepare(
                    match { it.shareId == "share-1" && it.whipUrl == "https://media.test/whip" },
                )
            }
            verify(exactly = 0) { screenSharePublisherController.start(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `screen capture denial stops only screen share and keeps voice call active`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        coEvery {
            createScreenShareUseCase.invoke("call-1", ScreenShareSource.Mobile)
        } returns AppResult.Success(sampleScreenShare(source = ScreenShareSource.Mobile))
        val viewModel = createViewModel()
        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                callId = "call-1",
                direction = CallDirection.Outgoing,
            ),
        )
        runCurrent()

        viewModel.effects.test {
            viewModel.onIntent(CallIntent.StartMobileScreenShare)
            advanceUntilIdle()
            awaitItem() as CallEffect.RequestScreenCapturePermission

            viewModel.onIntent(
                CallIntent.ScreenCapturePermissionResult(
                    resultCode = Activity.RESULT_CANCELED,
                    data = null,
                ),
            )
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(
                CallEffect.ShowMessage("未授予屏幕录制权限，语音通话不受影响"),
            )
            verify(exactly = 2) {
                screenSharePublisherController.permissionDenied(match { it.shareId == "share-1" })
            }
            coVerify(exactly = 1) { stopScreenShareUseCase.invoke("call-1") }
            coVerify(exactly = 0) { disconnectCallUseCase.invoke(any()) }
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Active)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `obs screen share exposes publishing credentials without starting mobile publisher`() =
        runTest(dispatcher) {
            sessionFlow.value = sampleSession(
                connectionState = CallConnectionState.Connected,
                localAudioState = LocalAudioState.Enabled,
                recordingState = RecordingState.Recording,
            )
            val share = sampleScreenShare(source = ScreenShareSource.Obs)
            coEvery {
                createScreenShareUseCase.invoke("call-1", ScreenShareSource.Obs)
            } returns AppResult.Success(share)
            val viewModel = createViewModel()
            viewModel.onIntent(
                CallIntent.OpenExistingCall(
                    pairId = "pair-1",
                    callId = "call-1",
                    direction = CallDirection.Outgoing,
                ),
            )
            runCurrent()

            viewModel.onIntent(CallIntent.SelectPublishQuality(ScreenShareQuality.HD60))
            viewModel.onIntent(CallIntent.StartObsScreenShare)
            advanceUntilIdle()

            assertThat(viewModel.state.value.screenShare.obsSetupVisible).isTrue()
            assertThat(viewModel.state.value.screenShare.publishQuality).isEqualTo(ScreenShareQuality.HD60)
            assertThat(viewModel.state.value.screenShare.obsPublishing).isEqualTo(share.publishing)
            verify(exactly = 0) { screenSharePublisherController.prepare(any()) }
            verify(exactly = 0) { screenSharePublisherController.start(any(), any()) }
        }

    @Test
    fun `remote live screen share starts WHEP playback`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val viewModel = createViewModel()
        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                callId = "call-1",
                direction = CallDirection.Outgoing,
            ),
        )
        runCurrent()

        screenShareFlow.value = ScreenShareSnapshot(
            callId = "call-1",
            session = sampleScreenShare(
                source = ScreenShareSource.Mobile,
                status = ScreenShareStatus.Live,
                publishing = null,
                playback = sampleMediaEndpoint("https://media.test/whep"),
            ),
            isOwnedByCurrentUser = false,
        )
        advanceUntilIdle()

        verify(exactly = 1) {
            whepPlaybackController.start(
                match { it.callId == "call-1" && it.shareId == "share-1" },
            )
        }
        assertThat(viewModel.state.value.screenShare.remoteState)
            .isEqualTo(RemoteScreenShareUiState.Connecting)
    }

    @Test
    fun `remote screen playback waits until LiveKit media is initialized`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Preparing,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val viewModel = createViewModel()
        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                callId = "call-1",
                direction = CallDirection.Outgoing,
            ),
        )
        runCurrent()

        screenShareFlow.value = ScreenShareSnapshot(
            callId = "call-1",
            session = sampleScreenShare(
                source = ScreenShareSource.Mobile,
                status = ScreenShareStatus.Live,
                publishing = null,
                playback = sampleMediaEndpoint("https://media.test/whep"),
            ),
            isOwnedByCurrentUser = false,
        )
        runCurrent()

        verify(exactly = 0) { whepPlaybackController.start(any()) }

        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        advanceUntilIdle()

        verify(exactly = 1) {
            whepPlaybackController.start(match { it.shareId == "share-1" })
        }
    }

    @Test
    fun `WHEP failure leaves LiveKit voice call active`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val endpoint = sampleMediaEndpoint("https://media.test/whep")
        val viewModel = createViewModel()
        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                callId = "call-1",
                direction = CallDirection.Outgoing,
            ),
        )
        runCurrent()
        screenShareFlow.value = ScreenShareSnapshot(
            callId = "call-1",
            session = sampleScreenShare(
                source = ScreenShareSource.Mobile,
                status = ScreenShareStatus.Live,
                publishing = null,
                playback = endpoint,
            ),
            isOwnedByCurrentUser = false,
        )
        advanceUntilIdle()
        val request = WhepPlaybackRequest(
            callId = "call-1",
            shareId = "share-1",
            url = endpoint.url,
            bearerToken = endpoint.bearerToken,
            expiresAtEpochMillis = endpoint.expiresAtEpochMillis,
        )

        whepStatusFlow.value = WhepPlaybackStatus.Failed(request, "ICE failed")
        advanceUntilIdle()

        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Active)
        assertThat(viewModel.state.value.screenShare.remoteState)
            .isEqualTo(RemoteScreenShareUiState.Failed)
        assertThat(viewModel.state.value.screenShare.errorMessage).isEqualTo("ICE failed")
        viewModel.onIntent(CallIntent.RetryRemoteScreenShare)
        viewModel.onIntent(CallIntent.RetryRemoteScreenShare)
        advanceUntilIdle()
        verify(exactly = 2) { whepPlaybackController.start(request) } // Initial play + one retry.
        assertThat(viewModel.state.value.screenShare.remoteState).isEqualTo(RemoteScreenShareUiState.Connecting)
        assertThat(viewModel.state.value.screenShare.errorMessage).isNull()
        coVerify(exactly = 0) { disconnectCallUseCase.invoke(any()) }
        viewModel.onIntent(CallIntent.EndCall)
        advanceUntilIdle()
        viewModel.onIntent(CallIntent.RetryRemoteScreenShare)
        verify(exactly = 2) { whepPlaybackController.start(request) }
    }

    @Test
    fun `ending call cleans up screen media and LiveKit session`() = runTest(dispatcher) {
        sessionFlow.value = sampleSession(
            connectionState = CallConnectionState.Connected,
            localAudioState = LocalAudioState.Enabled,
            recordingState = RecordingState.Recording,
        )
        val viewModel = createViewModel()
        viewModel.onIntent(
            CallIntent.OpenExistingCall(
                pairId = "pair-1",
                callId = "call-1",
                direction = CallDirection.Outgoing,
            ),
        )
        runCurrent()
        screenShareFlow.value = ScreenShareSnapshot(
            callId = "call-1",
            session = sampleScreenShare(
                source = ScreenShareSource.Mobile,
                status = ScreenShareStatus.Live,
                publishing = null,
                playback = sampleMediaEndpoint("https://media.test/whep"),
            ),
            isOwnedByCurrentUser = false,
        )
        advanceUntilIdle()

        viewModel.onIntent(CallIntent.EndCall)
        advanceUntilIdle()

        verify(exactly = 1) { screenSharePublisherController.stop("call-1") }
        verify(exactly = 1) { whepPlaybackController.stop("share-1") }
        coVerify(exactly = 1) { stopScreenShareUseCase.invoke("call-1") }
        coVerify(exactly = 1) { disconnectCallUseCase.invoke("call-1") }
        assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
    }

    private fun createViewModel(): CallViewModel = CallViewModel(
        prepareCallSessionUseCase = prepareCallSessionUseCase,
        cancelCallPreparationUseCase = cancelCallPreparationUseCase,
        prepareIncomingCallUseCase = prepareIncomingCallUseCase,
        connectCallUseCase = connectCallUseCase,
        observeCallStateUseCase = observeCallStateUseCase,
        toggleMuteUseCase = toggleMuteUseCase,
        selectAudioRouteUseCase = selectAudioRouteUseCase,
        disconnectCallUseCase = disconnectCallUseCase,
        observeScreenShareUseCase = observeScreenShareUseCase,
        createScreenShareUseCase = createScreenShareUseCase,
        stopScreenShareUseCase = stopScreenShareUseCase,
        screenSharePublisherController = screenSharePublisherController,
        whepPlaybackController = whepPlaybackController,
        audioLevelProvider = audioLevelProvider,
    )

    private fun sampleSession(
        callId: String = "call-1",
        connectionState: CallConnectionState,
        localAudioState: LocalAudioState,
        recordingState: RecordingState,
        remoteParticipantConnected: Boolean = true,
        interruptionState: CallInterruptionState = CallInterruptionState(),
    ) = CallSession(
        callId = callId,
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self", remote = "partner"),
        roomName = "room-1",
        connectionState = connectionState,
        localAudioState = localAudioState,
        recordingState = recordingState,
        interruptionState = interruptionState,
        uiSnapshot = CallUiSnapshot(
            activeAudioRoute = AudioRoute.Speaker,
            availableAudioRoutes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
            isForegroundServiceActive = true,
            remoteParticipantConnected = remoteParticipantConnected,
        ),
    )

    private fun sampleScreenShare(
        source: ScreenShareSource,
        status: ScreenShareStatus = ScreenShareStatus.Authorized,
        publishing: ScreenSharePublishing? = ScreenSharePublishing(
            whip = sampleMediaEndpoint("https://media.test/whip"),
        ),
        playback: ScreenShareMediaEndpoint? = null,
    ) = ScreenShareSession(
        shareId = "share-1",
        callId = "call-1",
        ownerUserId = "self",
        source = source,
        status = status,
        mediaPath = "calls/call-1/share-1",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 61_000L,
        publishing = publishing,
        playback = playback,
    )

    private fun sampleMediaEndpoint(url: String) = ScreenShareMediaEndpoint(
        url = url,
        bearerToken = "screen-token",
        expiresAtEpochMillis = Long.MAX_VALUE,
    )
}
