package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import app.cash.turbine.test
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
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.usecase.ConnectCallUseCase
import life.fxs.purr.domain.call.usecase.CancelCallPreparationUseCase
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase
import life.fxs.purr.domain.call.usecase.SelectAudioRouteUseCase
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
                assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
                assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Failed)
                assertThat(viewModel.state.value.failureMessage)
                    .isEqualTo("通话准备失败，请稍后重试")
                expectNoEvents()

                viewModel.onIntent(CallIntent.EndCall)
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
            assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
            assertThat(viewModel.state.value.screenState).isEqualTo(CallScreenState.Ended)
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
            assertThat(awaitItem()).isEqualTo(CallEffect.NavigateHome)
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

    private fun createViewModel(): CallViewModel = CallViewModel(
        prepareCallSessionUseCase = prepareCallSessionUseCase,
        cancelCallPreparationUseCase = cancelCallPreparationUseCase,
        prepareIncomingCallUseCase = prepareIncomingCallUseCase,
        connectCallUseCase = connectCallUseCase,
        observeCallStateUseCase = observeCallStateUseCase,
        toggleMuteUseCase = toggleMuteUseCase,
        selectAudioRouteUseCase = selectAudioRouteUseCase,
        disconnectCallUseCase = disconnectCallUseCase,
        audioLevelProvider = audioLevelProvider,
    )

    private fun sampleSession(
        callId: String = "call-1",
        connectionState: CallConnectionState,
        localAudioState: LocalAudioState,
        recordingState: RecordingState,
        remoteParticipantConnected: Boolean = true,
    ) = CallSession(
        callId = callId,
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self", remote = "partner"),
        roomName = "room-1",
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
