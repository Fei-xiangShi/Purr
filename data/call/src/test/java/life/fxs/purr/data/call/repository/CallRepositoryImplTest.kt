package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.SupervisorJob
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.NoOpPurrLogger
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.media.service.ForegroundCallServiceState
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetry
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetryOperation
import life.fxs.purr.core.media.telemetry.CallInterruptionTransitionContext
import life.fxs.purr.core.media.telemetry.NoOpCallInterruptionTelemetry
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.core.network.model.SessionResponseDto
import life.fxs.purr.data.call.remote.CallStatusRemoteDataSource
import life.fxs.purr.data.call.remote.CallStatusSynchronizer
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.MediaSystemCallInterruptionResult
import life.fxs.purr.data.call.runtime.CallRuntimeController
import life.fxs.purr.data.call.state.CallMediaEventReducer
import life.fxs.purr.data.call.state.CallUiSnapshotAssembler
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.LocalCallInterruption
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.model.RecordingState
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryImplTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<PurrCallApi>()
    private val callRuntimeController = mockk<CallRuntimeController>()
    private val audioRouteController = mockk<AudioRouteController>()
    private val callServiceController = mockk<CallServiceController>()
    private val callStatusRemoteDataSource = mockk<CallStatusRemoteDataSource>()
    private val applicationScope = CoroutineScope(dispatcher + SupervisorJob())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `late media events cannot revive a locally ended call`() = runTest(dispatcher) {
        val events = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
        configureConnectedSystemInterruptionCall(events)
        val repository = repository()
        runCurrent()
        establishConnectedCall(repository, events)
        runCurrent()
        repository.disconnectCall("call-1")
        runCurrent()

        events.emit(MediaCallEvent.Reconnecting("call-1", 1L))
        events.emit(MediaCallEvent.Connected("call-1", 1L, "self-1", "partner", true))
        events.emit(MediaCallEvent.Reconnected("call-1", 1L, "partner", true))
        events.emit(MediaCallEvent.ParticipantChanged("call-1", 1L, "partner", true))
        events.emit(MediaCallEvent.AudioStateChanged("call-1", 1L, false))
        runCurrent()

        val session = repository.observeCallSession().first()
        assertThat(session?.connectionState).isEqualTo(CallConnectionState.Disconnected)
        assertThat(session?.localAudioState).isEqualTo(LocalAudioState.Disabled)
        assertThat(session?.uiSnapshot?.remoteParticipantConnected).isFalse()
        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `preparation rejects a call that ended before status was fetched`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = false)
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1", pairId = "pair-1", state = "ended", recordingStatus = "idle",
        )
        val repository = repository()

        val result = repository.prepareCall(CallPreparationRequest.Existing(
            pairId = "pair-1", callId = "call-1", recordingConsent = true,
            direction = life.fxs.purr.core.model.CallDirection.Incoming,
        ))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.observeCallSession().first()).isNull()
        coVerify(exactly = 0) { callRuntimeController.execute(any()) }
    }

    @Test
    fun `new outgoing rejects legacy server reuse without ending the existing call`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = false)
        val repository = repository()

        val result = repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.observeCallSession().first()).isNull()
        coVerify(exactly = 0) { callStatusRemoteDataSource.getStatus(any()) }
        coVerify(exactly = 0) { callRuntimeController.execute(any()) }
        coVerify(exactly = 0) { api.endCall(any()) }
    }

    @Test
    fun `failed preparation never ends an existing incoming call joined by this request`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = false)
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } throws IllegalStateException("status unavailable")
        coEvery { api.endCall(any()) } returns Unit
        val repository = repository()

        val result = repository.prepareCall(
            CallPreparationRequest.Existing(
                pairId = "pair-1",
                callId = "call-1",
                recordingConsent = true,
                direction = life.fxs.purr.core.model.CallDirection.Incoming,
            ),
        )

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        coVerify(exactly = 1) {
            api.createSession(match { it.expectedCallId == "call-1" })
        }
        coVerify(exactly = 0) { api.endCall(any()) }
    }

    @Test
    fun `failed preparation compensates only a call created by this request`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = true)
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } throws IllegalStateException("status unavailable")
        coEvery { api.endCall("call-1") } returns Unit
        val repository = repository()

        val result = repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `mismatched incoming response is rejected and compensates an owned call`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns
            sessionResponse(createdByRequest = true).copy(callId = "call-other")
        coEvery { api.endCall("call-other") } returns Unit
        val repository = repository()

        val result = repository.prepareCall(
            CallPreparationRequest.Existing(
                pairId = "pair-1",
                callId = "call-1",
                recordingConsent = true,
                direction = life.fxs.purr.core.model.CallDirection.Incoming,
            ),
        )

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        coVerify(exactly = 1) { api.endCall("call-other") }
        coVerify(exactly = 0) { callStatusRemoteDataSource.getStatus(any()) }
    }

    @Test
    fun `status sync disconnects local call when server marks call ended`() = runTest(dispatcher) {
        val foreground = MutableStateFlow(ForegroundCallServiceState(activeCallId = "call-1"))
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns foreground
        every { callRuntimeController.mediaEvents } returns emptyFlow()
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } coAnswers {
            foreground.emit(ForegroundCallServiceState())
        }
        coEvery { callRuntimeController.releaseResources() } returns Unit
        coEvery { api.endCall("call-1") } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            callId = "call-1",
            pairId = "pair-1",
            roomName = "room-1",
            participantIdentity = "self",
            token = "token",
            wsUrl = "ws://example.invalid",
            createdByRequest = true,
        )
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "idle",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns flowOf(
            CallStatusDto(
                callId = "call-1",
                pairId = "pair-1",
                state = "ended",
                recordingStatus = "stopped",
                startedAtEpochMillis = 1L,
                endedAtEpochMillis = 2L,
            ),
        )
        val repository = repository()

        val result = repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        advanceUntilIdle()

        val session = repository.observeCallSession().first { it?.connectionState == CallConnectionState.Disconnected }
        assertThat(session?.connectionState).isEqualTo(CallConnectionState.Disconnected)
        assertThat(session?.localAudioState).isEqualTo(LocalAudioState.Disabled)
        assertThat(session?.recordingState).isEqualTo(RecordingState.NotRecording)
        assertThat(session?.timing?.startedAtEpochMillis).isEqualTo(1L)
        assertThat(session?.timing?.endedAtEpochMillis).isEqualTo(2L)
        assertThat(session?.timing?.synchronizedDurationMillis).isEqualTo(1L)
        assertThat(session?.timing?.isRunning).isFalse()
        assertThat(session?.uiSnapshot?.remoteParticipantConnected).isEqualTo(false)
        assertThat(session?.uiSnapshot?.isForegroundServiceActive).isEqualTo(false)

        coVerify(exactly = 1) {
            callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
        }
        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `leaving call ends server call and disconnects locally`() = runTest(dispatcher) {
        val foreground = MutableStateFlow(ForegroundCallServiceState(activeCallId = "call-1"))
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns foreground
        every { callRuntimeController.mediaEvents } returns emptyFlow()
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } returns Unit
        coEvery { callRuntimeController.releaseResources() } coAnswers {
            foreground.emit(ForegroundCallServiceState())
        }
        coEvery { api.endCall("call-1") } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            callId = "call-1",
            pairId = "pair-1",
            roomName = "room-1",
            participantIdentity = "self",
            token = "token",
            wsUrl = "ws://example.invalid",
            createdByRequest = true,
        )
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "active",
            recordingStatus = "idle",
            startedAtEpochMillis = 1L,
            durationMillis = 5_000L,
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        val repository = repository()

        repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))
        val preparedSession = repository.observeCallSession().first()
        assertThat(preparedSession?.timing?.synchronizedDurationMillis).isEqualTo(5_000L)
        assertThat(preparedSession?.timing?.isRunning).isTrue()
        val result = repository.disconnectCall("call-1")

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(repository.observeCallSession().first()?.timing?.isRunning).isFalse()
        coVerify(exactly = 1) { api.endCall("call-1") }
        coVerifyOrder {
            api.endCall("call-1")
            callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
        }
    }

    @Test
    fun `server end wait does not hold state mutex and concurrent disconnect is deduplicated`() = runTest(dispatcher) {
        val serverEndStarted = CompletableDeferred<Unit>()
        val finishServerEnd = CompletableDeferred<Unit>()
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(
            ForegroundCallServiceState(activeCallId = "call-1"),
        )
        every { callRuntimeController.mediaEvents } returns emptyFlow()
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } returns Unit
        coEvery { api.endCall("call-1") } coAnswers {
            serverEndStarted.complete(Unit)
            finishServerEnd.await()
        }
        coEvery { api.createSession(any()) } returnsMany listOf(
            SessionResponseDto("call-1", "pair-1", "room-1", "self-1", "token-1", "wss://one", createdByRequest = true),
            SessionResponseDto("call-2", "pair-1", "room-2", "self-2", "token-2", "wss://two", createdByRequest = true),
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } answers {
            CallStatusDto(
                callId = firstArg(),
                pairId = "pair-1",
                state = "active",
                recordingStatus = "idle",
                startedAtEpochMillis = 1L,
            )
        }
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        val repository = repository()
        repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))

        val firstDisconnect = async { repository.disconnectCall("call-1") }
        serverEndStarted.await()
        // Server synchronization starts first, but local teardown is still an
        // application-scoped operation and must be allowed to run before the
        // assertion below; it never waits for the remote request.
        runCurrent()
        val duplicateDisconnect = async { repository.disconnectCall("call-1") }

        val muteWhileServerWaits = repository.setMuted(muted = true)
        assertThat(muteWhileServerWaits).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Disconnected)
        val pendingLifecycle = repository.observeCallLifecycle().first()
        assertThat(pendingLifecycle.resumableSession).isNull()
        assertThat(pendingLifecycle.isTerminationInProgress).isFalse()
        assertThat(pendingLifecycle.isNewCallBlocked).isFalse()
        assertThat(pendingLifecycle.disconnectingCallId).isNull()
        val queuedNextCall = async { repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true)) }
        runCurrent()
        assertThat(queuedNextCall.isCompleted).isTrue()
        assertThat(firstDisconnect.isCompleted).isTrue()
        assertThat(duplicateDisconnect.isCompleted).isTrue()

        assertThat(firstDisconnect.await()).isInstanceOf(AppResult.Success::class.java)
        finishServerEnd.complete(Unit)
        assertThat(duplicateDisconnect.await()).isInstanceOf(AppResult.Success::class.java)
        assertThat((queuedNextCall.await() as AppResult.Success).value.callId).isEqualTo("call-2")
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Preparing)
        val completedLifecycle = repository.observeCallLifecycle().first()
        assertThat(completedLifecycle.isTerminationInProgress).isFalse()
        assertThat(completedLifecycle.session?.callId).isEqualTo("call-2")
        // A new call is immediately admissible; the previous call's server
        // synchronization is independent and must not block this lifecycle.
        assertThat(completedLifecycle.isNewCallBlocked).isFalse()
        assertThat(completedLifecycle.disconnectingCallId).isNull()

        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `termination deadline releases lifecycle when local cleanup never returns`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = true)
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "active",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        coEvery { callRuntimeController.execute(MediaCallCommand.Disconnect("call-1")) } coAnswers {
            awaitCancellation()
        }
        coEvery { api.endCall("call-1") } returns Unit
        val repository = repository()
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))

        val disconnect = async { repository.disconnectCall("call-1") }
        runCurrent()

        assertThat(repository.observeCallLifecycle().first().isTerminationInProgress).isTrue()
        advanceTimeBy(5_000L)
        runCurrent()

        assertThat(disconnect.await()).isInstanceOf(AppResult.Failure::class.java)
        val lifecycle = repository.observeCallLifecycle().first()
        assertThat(lifecycle.isTerminationInProgress).isFalse()
        assertThat(lifecycle.isNewCallBlocked).isFalse()
        assertThat(lifecycle.disconnectingCallId).isNull()
        assertThat(lifecycle.session?.connectionState)
            .isInstanceOf(CallConnectionState.Failed::class.java)
        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `server end timeout releases lifecycle and admits the next call`() = runTest(dispatcher) {
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returnsMany listOf(
            sessionResponse(createdByRequest = true),
            SessionResponseDto(
                callId = "call-2",
                pairId = "pair-1",
                roomName = "room-2",
                participantIdentity = "self",
                token = "token-2",
                wsUrl = "wss://example.invalid",
                createdByRequest = true,
            ),
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } answers {
            CallStatusDto(
                callId = firstArg(),
                pairId = "pair-1",
                state = "active",
                recordingStatus = "idle",
            )
        }
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        coEvery { callRuntimeController.execute(MediaCallCommand.Disconnect("call-1")) } returns Unit
        coEvery { api.endCall("call-1") } coAnswers { awaitCancellation() }
        val repository = repository()
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))

        val disconnect = async { repository.disconnectCall("call-1") }
        runCurrent()

        val pending = repository.observeCallLifecycle().first()
        assertThat(pending.session?.connectionState).isEqualTo(CallConnectionState.Disconnected)
        assertThat(pending.isTerminationInProgress).isFalse()
        assertThat(pending.isNewCallBlocked).isFalse()
        advanceTimeBy(5_000L)
        runCurrent()

        assertThat(disconnect.await()).isInstanceOf(AppResult.Success::class.java)
        val completed = repository.observeCallLifecycle().first()
        assertThat(completed.isTerminationInProgress).isFalse()
        assertThat(completed.isNewCallBlocked).isFalse()
        assertThat(completed.disconnectingCallId).isNull()

        val nextCall = repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        assertThat((nextCall as AppResult.Success).value.callId).isEqualTo("call-2")
        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `cleanup cancellation still clears termination owner and publishes terminal state`() =
        runTest(dispatcher) {
            configureIdleRuntime()
            coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = true)
            coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
                callId = "call-1",
                pairId = "pair-1",
                state = "active",
                recordingStatus = "idle",
            )
            every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
            coEvery { api.endCall("call-1") } returns Unit
            coEvery {
                callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
            } throws CancellationException("runtime cleanup cancelled")
            val repository = repository()
            repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))

            val result = runCatching { repository.disconnectCall("call-1") }

            assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            val lifecycle = repository.observeCallLifecycle().first()
            assertThat(lifecycle.isTerminationInProgress).isFalse()
            assertThat(lifecycle.isNewCallBlocked).isFalse()
            assertThat(lifecycle.disconnectingCallId).isNull()
            assertThat(lifecycle.session?.connectionState)
                .isInstanceOf(CallConnectionState.Failed::class.java)
            coVerify(exactly = 1) { api.endCall("call-1") }
        }

    @Test
    fun `local cleanup failure publishes failed state while server reconciliation remains pending`() = runTest(dispatcher) {
        val serverEndStarted = CompletableDeferred<Unit>()
        val finishServerEnd = CompletableDeferred<Unit>()
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returns sessionResponse(createdByRequest = true)
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "active",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        coEvery { callRuntimeController.execute(MediaCallCommand.Disconnect("call-1")) } throws
            IllegalStateException("local cleanup failed")
        coEvery { callRuntimeController.releaseResources() } returns Unit
        coEvery { api.endCall("call-1") } coAnswers {
            serverEndStarted.complete(Unit)
            finishServerEnd.await()
        }
        val repository = repository()
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))

        val disconnect = async { repository.disconnectCall("call-1") }
        serverEndStarted.await()
        runCurrent()

        val localState = repository.observeCallSession().first()?.connectionState
        assertThat(localState).isInstanceOf(CallConnectionState.Failed::class.java)
        assertThat(disconnect.await()).isInstanceOf(AppResult.Failure::class.java)
        val lifecycle = repository.observeCallLifecycle().first()
        assertThat(lifecycle.isTerminationInProgress).isFalse()
        assertThat(lifecycle.isNewCallBlocked).isFalse()

        finishServerEnd.complete(Unit)
        runCurrent()
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isInstanceOf(CallConnectionState.Failed::class.java)
    }

    @Test
    fun `ended session cannot be connected again`() = runTest(dispatcher) {
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(ForegroundCallServiceState())
        every { callRuntimeController.mediaEvents } returns emptyFlow()
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } returns Unit
        coEvery { api.endCall("call-1") } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            callId = "call-1",
            pairId = "pair-1",
            roomName = "room-1",
            participantIdentity = "self",
            token = "token",
            wsUrl = "ws://example.invalid",
            createdByRequest = true,
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } answers { CallStatusDto(
            callId = firstArg(),
            pairId = "pair-1",
            state = "waiting",
            recordingStatus = "idle",
        ) }
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        val repository = repository()

        repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))
        repository.disconnectCall("call-1")
        val duplicateDisconnect = repository.disconnectCall("call-1")

        val result = repository.connectCall()

        assertThat(duplicateDisconnect).isInstanceOf(AppResult.Success::class.java)
        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        coVerify(exactly = 1) { api.endCall("call-1") }
        coVerify(exactly = 1) {
            callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
        }
    }

    @Test
    fun `media disconnect ends server call before a new session is admitted`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 4)
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(
            ForegroundCallServiceState(activeCallId = "call-1"),
        )
        every { callRuntimeController.mediaEvents } returns runtimeEvents
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Connect>()) } returns Unit
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } returns Unit
        coEvery { api.endCall("call-1") } returns Unit
        coEvery { api.createSession(any()) } returnsMany listOf(
            SessionResponseDto("call-1", "pair-1", "room-1", "self-1", "token-1", "wss://one", createdByRequest = true),
            SessionResponseDto("call-2", "pair-1", "room-2", "self-2", "token-2", "wss://two", createdByRequest = true),
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } answers {
            CallStatusDto(
                callId = firstArg(),
                pairId = "pair-1",
                state = "active",
                recordingStatus = "idle",
                startedAtEpochMillis = 1L,
            )
        }
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        val repository = repository()
        runCurrent()

        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        repository.connectCall()
        runtimeEvents.emit(
            MediaCallEvent.Connected(
                callId = "call-1",
                generation = 1L,
                localIdentity = "self-1",
                remoteIdentity = "partner",
                remoteParticipantConnected = true,
            ),
        )
        runCurrent()
        runtimeEvents.emit(MediaCallEvent.Disconnected(callId = "call-1", generation = 1L))
        advanceUntilIdle()

        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Disconnected)
        coVerify(exactly = 1) { api.endCall("call-1") }
        coVerify(exactly = 1) {
            callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
        }

        val nextCall = repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        assertThat((nextCall as AppResult.Success).value.callId).isEqualTo("call-2")
    }

    @Test
    fun `media reconnect never ends the server call and restores connected state`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 4)
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(
            ForegroundCallServiceState(activeCallId = "call-1"),
        )
        every { callRuntimeController.mediaEvents } returns runtimeEvents
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Connect>()) } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            "call-1",
            "pair-1",
            "room-1",
            "self-1",
            "token-1",
            "wss://one",
            createdByRequest = true,
        )
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "active",
            recordingStatus = "idle",
            startedAtEpochMillis = 1L,
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        val repository = repository()
        runCurrent()

        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        repository.connectCall()
        runtimeEvents.emit(
            MediaCallEvent.Connected(
                callId = "call-1",
                generation = 1L,
                localIdentity = "self-1",
                remoteIdentity = "partner",
                remoteParticipantConnected = true,
            ),
        )
        runCurrent()
        runtimeEvents.emit(MediaCallEvent.Reconnecting(callId = "call-1", generation = 1L))
        runCurrent()

        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Reconnecting)
        coVerify(exactly = 0) { api.endCall(any()) }
        coVerify(exactly = 0) {
            callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
        }

        runtimeEvents.emit(
            MediaCallEvent.Reconnected(
                callId = "call-1",
                generation = 1L,
                remoteIdentity = "partner",
                remoteParticipantConnected = true,
            ),
        )
        runCurrent()

        val restored = repository.observeCallSession().first()
        assertThat(restored?.connectionState).isEqualTo(CallConnectionState.Connected)
        assertThat(restored?.localAudioState).isEqualTo(LocalAudioState.Enabled)
        coVerify(exactly = 0) { api.endCall(any()) }
        coVerify(exactly = 0) {
            callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
        }
    }

    @Test
    fun `mute success and unmute rollback preserve route until its flow changes`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 4)
        val activeRoute = MutableStateFlow<AudioRoute>(AudioRoute.Earpiece)
        val availableRoutes = MutableStateFlow(listOf(AudioRoute.Earpiece, AudioRoute.Speaker))
        var exposedActiveRoute: StateFlow<AudioRoute> = activeRoute
        var exposedAvailableRoutes: StateFlow<List<AudioRoute>> = availableRoutes
        every { audioRouteController.activeRoute } answers { exposedActiveRoute }
        every { audioRouteController.availableRoutes } answers { exposedAvailableRoutes }
        every { callServiceController.foregroundState } returns MutableStateFlow(
            ForegroundCallServiceState(activeCallId = "call-1"),
        )
        every { callRuntimeController.mediaEvents } returns runtimeEvents
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Connect>()) } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            "call-1",
            "pair-1",
            "room-1",
            "self-1",
            "token-1",
            "wss://one",
            createdByRequest = true,
        )
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "active",
            recordingStatus = "idle",
            startedAtEpochMillis = 1L,
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        val repository = repository()
        runCurrent()
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        repository.connectCall()
        runtimeEvents.emit(
            MediaCallEvent.Connected(
                callId = "call-1",
                generation = 1L,
                localIdentity = "self-1",
                remoteIdentity = "partner",
                remoteParticipantConnected = true,
            ),
        )
        runCurrent()
        coEvery {
            callRuntimeController.execute(MediaCallCommand.SetMuted("call-1", muted = true))
        } coAnswers {
            // Replacing the exposed snapshots simulates an unrelated synchronous re-sample.
            // The repository must remain subscribed to the original runtime flows.
            exposedActiveRoute = MutableStateFlow(AudioRoute.Speaker)
            exposedAvailableRoutes = MutableStateFlow(listOf(AudioRoute.Speaker))
        }
        val muteResult = repository.setMuted(muted = true)

        assertThat(muteResult).isInstanceOf(AppResult.Success::class.java)
        assertThat(repository.observeCallSession().first()?.localAudioState)
            .isEqualTo(LocalAudioState.Muted)
        assertThat(repository.observeCallSession().first()?.uiSnapshot?.activeAudioRoute)
            .isEqualTo(AudioRoute.Earpiece)
        coEvery {
            callRuntimeController.execute(MediaCallCommand.SetMuted("call-1", muted = false))
        } throws IllegalStateException("audio profile restore failed")

        val result = repository.setMuted(muted = false)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.observeCallSession().first()?.localAudioState)
            .isEqualTo(LocalAudioState.Muted)
        assertThat(repository.observeCallSession().first()?.uiSnapshot?.activeAudioRoute)
            .isEqualTo(AudioRoute.Earpiece)
        assertThat(repository.observeCallSession().first()?.uiSnapshot?.availableAudioRoutes)
            .containsExactly(AudioRoute.Earpiece, AudioRoute.Speaker).inOrder()

        activeRoute.value = AudioRoute.Speaker
        availableRoutes.value = listOf(AudioRoute.Speaker)
        runCurrent()

        assertThat(repository.observeCallSession().first()?.uiSnapshot?.activeAudioRoute)
            .isEqualTo(AudioRoute.Speaker)
        assertThat(repository.observeCallSession().first()?.uiSnapshot?.availableAudioRoutes)
            .containsExactly(AudioRoute.Speaker)
    }

    @Test
    fun `delayed runtime event from a previous call cannot terminate the current call`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 4)
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(ForegroundCallServiceState())
        every { callRuntimeController.mediaEvents } returns runtimeEvents
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } returns Unit
        coEvery { callRuntimeController.releaseResources() } returns Unit
        coEvery { api.endCall(any()) } returns Unit
        coEvery { api.createSession(any()) } returnsMany listOf(
            SessionResponseDto("call-1", "pair-1", "room-1", "self-1", "token-1", "wss://one", createdByRequest = true),
            SessionResponseDto("call-2", "pair-1", "room-2", "self-2", "token-2", "wss://two", createdByRequest = true),
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "waiting",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        val repository = repository()

        val first = (repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true)) as AppResult.Success).value
        repository.disconnectCall("call-1")
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        advanceUntilIdle()

        runtimeEvents.emit(
            MediaCallEvent.Disconnected(
                callId = first.callId,
                generation = 1L,
            ),
        )
        advanceUntilIdle()

        val current = repository.observeCallSession().first { it?.callId == "call-2" }
        assertThat(current?.connectionState).isEqualTo(CallConnectionState.Preparing)
        coVerify(exactly = 0) { callRuntimeController.releaseResources() }
    }

    @Test
    fun `termination signal stops application connect owner without blocking the next call`() = runTest(dispatcher) {
        val connectStarted = CompletableDeferred<Unit>()
        val connectCancelled = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        configureIdleRuntime()
        coEvery { api.createSession(any()) } returnsMany listOf(
            sessionResponse(createdByRequest = true),
            sessionResponse(createdByRequest = true).copy(
                callId = "call-2",
                roomName = "room-2",
            ),
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } answers { CallStatusDto(
            callId = firstArg(),
            pairId = "pair-1",
            state = "waiting",
            recordingStatus = "idle",
        ) }
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Connect>()) } coAnswers {
            val command = firstArg<MediaCallCommand.Connect>()
            connectStarted.complete(Unit)
            try {
                command.terminationSignal.runStage { awaitCancellation() }
            } finally {
                connectCancelled.complete(Unit)
            }
        }
        coEvery { callRuntimeController.execute(MediaCallCommand.Disconnect("call-1")) } coAnswers {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
        }
        coEvery { api.endCall("call-1") } returns Unit
        val repository = repository()
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))

        val connectCaller = async { repository.connectCall() }
        connectStarted.await()
        connectCaller.cancelAndJoin()
        val disconnect = async { repository.disconnectCall("call-1") }
        connectCancelled.await()
        cleanupStarted.await()

        val nextCall = async { repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true)) }
        runCurrent()
        assertThat(nextCall.isCompleted).isTrue()
        assertThat(disconnect.isCompleted).isFalse()
        val nextResult = nextCall.await()
        assertThat(nextResult).isInstanceOf(AppResult.Success::class.java)
        assertThat((nextResult as AppResult.Success).value.callId).isEqualTo("call-2")

        releaseCleanup.complete(Unit)
        assertThat(disconnect.await()).isInstanceOf(AppResult.Success::class.java)
        coVerify(exactly = 1) { callRuntimeController.execute(MediaCallCommand.Disconnect("call-1")) }
        coVerify(exactly = 1) { api.endCall("call-1") }
    }

    @Test
    fun `application scoped prepare and connect survive caller cancellation`() = runTest(dispatcher) {
        val createStarted = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val connectStarted = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(ForegroundCallServiceState())
        every { callRuntimeController.mediaEvents } returns emptyFlow()
        coEvery { api.createSession(any()) } coAnswers {
            createStarted.complete(Unit)
            releaseCreate.await()
            SessionResponseDto(
                callId = "call-1",
                pairId = "pair-1",
                roomName = "room-1",
                participantIdentity = "self",
                token = "token",
                wsUrl = "ws://example.invalid",
                createdByRequest = true,
            )
        }
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "waiting",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Connect>()) } coAnswers {
            connectStarted.complete(Unit)
            releaseConnect.await()
        }
        val repository = repository()

        val prepareCaller = async {
            repository.prepareCall(CallPreparationRequest.NewOutgoing(pairId = "pair-1", recordingConsent = true))
        }
        runCurrent()
        createStarted.await()
        prepareCaller.cancelAndJoin()

        releaseCreate.complete(Unit)
        advanceUntilIdle()
        assertThat(repository.observeCallSession().first()?.callId).isEqualTo("call-1")

        val connectCaller = async { repository.connectCall() }
        runCurrent()
        connectStarted.await()
        connectCaller.cancelAndJoin()

        releaseConnect.complete(Unit)
        advanceUntilIdle()
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Connecting)
        coVerify(exactly = 1) {
            callRuntimeController.execute(
                match {
                    it is MediaCallCommand.Connect &&
                        it.callId == "call-1" &&
                        it.pairId == "pair-1" &&
                        it.localIdentity == "self"
                },
            )
        }
    }

    @Test
    fun `resume performs one immediate attempt plus twenty retries then disconnects once`() =
        runTest(dispatcher) {
            val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
            configureConnectedSystemInterruptionCall(runtimeEvents)
            coEvery { callRuntimeController.suspendForSystemCall(any()) } returns
                MediaSystemCallInterruptionResult.Applied(generation = 1L)
            coEvery { callRuntimeController.resumeAfterSystemCall(any()) } returns
                MediaSystemCallInterruptionResult.Failed(
                    generation = 1L,
                    reasonCode = "restore_failed",
                )
            val telemetry = RecordingInterruptionTelemetry()
            val repository = repository(telemetry)
            establishConnectedCall(repository, runtimeEvents)
            runCurrent()

            val suspendResult = repository.suspendForSystemCall(interruptionRequest(sequence = 1L))
            val resumeResult = repository.resumeAfterSystemCall(interruptionRequest(sequence = 2L))

            assertThat(suspendResult).isEqualTo(SystemCallInterruptionResult.Applied)
            assertThat(resumeResult).isEqualTo(SystemCallInterruptionResult.RetryScheduled)
            advanceTimeBy(20_000L)
            runCurrent()
            advanceUntilIdle()

            coVerify(exactly = 21) { callRuntimeController.resumeAfterSystemCall(any()) }
            coVerify(exactly = 1) {
                callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
            }
            coVerify(exactly = 1) { api.endCall("call-1") }
            assertThat(telemetry.finalFailures).isEqualTo(1)
            assertThat(telemetry.attempts).isEqualTo(22)
            assertThat(repository.observeCallSession().first()?.connectionState)
                .isEqualTo(CallConnectionState.Disconnected)
        }

    @Test
    fun `success on retry twenty restores call without disconnecting`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
        configureConnectedSystemInterruptionCall(runtimeEvents)
        var attempts = 0
        coEvery { callRuntimeController.suspendForSystemCall(any()) } returns
            MediaSystemCallInterruptionResult.Applied(generation = 1L)
        coEvery { callRuntimeController.resumeAfterSystemCall(any()) } coAnswers {
            attempts += 1
            if (attempts == 21) {
                MediaSystemCallInterruptionResult.Applied(generation = 1L)
            } else {
                MediaSystemCallInterruptionResult.Failed(
                    generation = 1L,
                    reasonCode = "restore_failed",
                )
            }
        }
        val repository = repository()
        establishConnectedCall(repository, runtimeEvents)
        runCurrent()

        repository.suspendForSystemCall(interruptionRequest(sequence = 1L))
        repository.resumeAfterSystemCall(interruptionRequest(sequence = 2L))
        advanceTimeBy(20_000L)
        runCurrent()

        assertThat(attempts).isEqualTo(21)
        assertThat(repository.observeCallSession().first()?.interruptionState?.local)
            .isEqualTo(LocalCallInterruption.None)
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Connected)
        coVerify(exactly = 0) {
            callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
        }
        coVerify(exactly = 0) { api.endCall(any()) }
    }

    @Test
    fun `reconnecting pauses resume retries without consuming an attempt`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
        configureConnectedSystemInterruptionCall(runtimeEvents)
        var attempts = 0
        coEvery { callRuntimeController.suspendForSystemCall(any()) } returns
            MediaSystemCallInterruptionResult.Applied(generation = 1L)
        coEvery { callRuntimeController.resumeAfterSystemCall(any()) } coAnswers {
            attempts += 1
            if (attempts == 1) {
                MediaSystemCallInterruptionResult.Failed(
                    generation = 1L,
                    reasonCode = "restore_failed",
                )
            } else {
                MediaSystemCallInterruptionResult.Applied(generation = 1L)
            }
        }
        val repository = repository()
        establishConnectedCall(repository, runtimeEvents)
        runCurrent()
        repository.suspendForSystemCall(interruptionRequest(sequence = 1L))
        repository.resumeAfterSystemCall(interruptionRequest(sequence = 2L))

        runtimeEvents.emit(MediaCallEvent.Reconnecting(callId = "call-1", generation = 1L))
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()

        assertThat(attempts).isEqualTo(1)
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Reconnecting)

        runtimeEvents.emit(
            MediaCallEvent.Reconnected(
                callId = "call-1",
                generation = 1L,
                remoteIdentity = "partner",
                remoteParticipantConnected = true,
            ),
        )
        runCurrent()

        assertThat(attempts).isEqualTo(2)
        assertThat(repository.observeCallSession().first()?.interruptionState?.local)
            .isEqualTo(LocalCallInterruption.None)
        coVerify(exactly = 0) {
            callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
        }
    }

    @Test
    fun `reconnecting during a resume attempt retries the same attempt after reconnect`() =
        runTest(dispatcher) {
            val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
            configureConnectedSystemInterruptionCall(runtimeEvents)
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val finishFirstAttempt = CompletableDeferred<Unit>()
            var attempts = 0
            coEvery { callRuntimeController.suspendForSystemCall(any()) } returns
                MediaSystemCallInterruptionResult.Applied(generation = 1L)
            coEvery { callRuntimeController.resumeAfterSystemCall(any()) } coAnswers {
                attempts += 1
                if (attempts == 1) {
                    firstAttemptStarted.complete(Unit)
                    finishFirstAttempt.await()
                    MediaSystemCallInterruptionResult.Failed(
                        generation = 1L,
                        reasonCode = "restore_failed",
                    )
                } else {
                    MediaSystemCallInterruptionResult.Applied(generation = 1L)
                }
            }
            val repository = repository()
            establishConnectedCall(repository, runtimeEvents)
            runCurrent()
            repository.suspendForSystemCall(interruptionRequest(sequence = 1L))

            val resumeResult = async {
                repository.resumeAfterSystemCall(interruptionRequest(sequence = 2L))
            }
            runCurrent()
            firstAttemptStarted.await()
            runtimeEvents.emit(MediaCallEvent.Reconnecting(callId = "call-1", generation = 1L))
            runCurrent()
            finishFirstAttempt.complete(Unit)
            runCurrent()

            assertThat(resumeResult.await()).isEqualTo(SystemCallInterruptionResult.RetryScheduled)
            advanceTimeBy(30_000L)
            runCurrent()
            assertThat(attempts).isEqualTo(1)

            runtimeEvents.emit(
                MediaCallEvent.Reconnected(
                    callId = "call-1",
                    generation = 1L,
                    remoteIdentity = "partner",
                    remoteParticipantConnected = true,
                ),
            )
            runCurrent()

            assertThat(attempts).isEqualTo(2)
            assertThat(repository.observeCallSession().first()?.interruptionState?.local)
                .isEqualTo(LocalCallInterruption.None)
            coVerify(exactly = 0) {
                callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
            }
        }

    @Test
    fun `a newer inactive operation cancels the old resume retry owner`() = runTest(dispatcher) {
        val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
        configureConnectedSystemInterruptionCall(runtimeEvents)
        var resumeAttempts = 0
        coEvery { callRuntimeController.suspendForSystemCall(any()) } returns
            MediaSystemCallInterruptionResult.Applied(generation = 1L)
        coEvery { callRuntimeController.resumeAfterSystemCall(any()) } coAnswers {
            resumeAttempts += 1
            MediaSystemCallInterruptionResult.Failed(
                generation = 1L,
                reasonCode = "restore_failed",
            )
        }
        val repository = repository()
        establishConnectedCall(repository, runtimeEvents)
        runCurrent()
        repository.suspendForSystemCall(
            interruptionRequest(sequence = 1L, operationId = "operation-1"),
        )
        repository.resumeAfterSystemCall(
            interruptionRequest(sequence = 2L, operationId = "operation-1"),
        )

        val secondSuspend = repository.suspendForSystemCall(
            interruptionRequest(sequence = 3L, operationId = "operation-2"),
        )
        advanceTimeBy(30_000L)
        runCurrent()

        assertThat(secondSuspend).isEqualTo(SystemCallInterruptionResult.Applied)
        assertThat(resumeAttempts).isEqualTo(1)
        assertThat(repository.observeCallSession().first()?.interruptionState?.local)
            .isEqualTo(LocalCallInterruption.Suspended("operation-2", degraded = false))
        coVerify(exactly = 0) {
            callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
        }
    }

    @Test
    fun `suspension preserves foreground service recording timing and server call`() =
        runTest(dispatcher) {
            val runtimeEvents = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = 8)
            configureConnectedSystemInterruptionCall(
                runtimeEvents = runtimeEvents,
                recordingStatus = "recording",
            )
            coEvery { callRuntimeController.suspendForSystemCall(any()) } returns
                MediaSystemCallInterruptionResult.Applied(generation = 1L)
            val repository = repository()
            establishConnectedCall(repository, runtimeEvents)
            runCurrent()
            val before = requireNotNull(repository.observeCallSession().first())

            val result = repository.suspendForSystemCall(interruptionRequest(sequence = 1L))
            val after = requireNotNull(repository.observeCallSession().first())

            assertThat(result).isEqualTo(SystemCallInterruptionResult.Applied)
            assertThat(after.connectionState).isEqualTo(CallConnectionState.Connected)
            assertThat(after.recordingState).isEqualTo(RecordingState.Recording)
            assertThat(after.timing).isEqualTo(before.timing)
            assertThat(after.timing.isRunning).isTrue()
            assertThat(callServiceController.foregroundState.value.activeCallId).isEqualTo("call-1")
            coVerify(exactly = 0) { callServiceController.stopForegroundCall(any()) }
            coVerify(exactly = 0) {
                callRuntimeController.execute(match { it is MediaCallCommand.Disconnect })
            }
            coVerify(exactly = 0) { api.endCall(any()) }
        }

    private fun repository(
        interruptionTelemetry: CallInterruptionTelemetry = NoOpCallInterruptionTelemetry,
    ) = CallRepositoryImpl(
        api = api,
        sessionPreparationCoordinator = CallSessionPreparationCoordinator(
            api = api,
            callStatusRemoteDataSource = callStatusRemoteDataSource,
            callUiSnapshotAssembler = CallUiSnapshotAssembler(audioRouteController, callServiceController),
            logger = NoOpPurrLogger,
        ),
        callRuntimeController = callRuntimeController,
        callStatusSynchronizer = CallStatusSynchronizer(callStatusRemoteDataSource, applicationScope),
        callMediaEventReducer = CallMediaEventReducer(),
        callUiSnapshotAssembler = CallUiSnapshotAssembler(audioRouteController, callServiceController),
        logger = NoOpPurrLogger,
        applicationScope = applicationScope,
        interruptionTelemetry = interruptionTelemetry,
    )

    private fun configureConnectedSystemInterruptionCall(
        runtimeEvents: MutableSharedFlow<MediaCallEvent>,
        recordingStatus: String = "idle",
    ) {
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(
            ForegroundCallServiceState(activeCallId = "call-1"),
        )
        every { callRuntimeController.mediaEvents } returns runtimeEvents
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Connect>()) } returns Unit
        coEvery { callRuntimeController.execute(any<MediaCallCommand.Disconnect>()) } returns Unit
        coEvery { api.endCall("call-1") } returns Unit
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            "call-1",
            "pair-1",
            "room-1",
            "self-1",
            "token-1",
            "wss://one",
            createdByRequest = true,
        )
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "active",
            recordingStatus = recordingStatus,
            startedAtEpochMillis = 1L,
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
    }

    private suspend fun establishConnectedCall(
        repository: CallRepositoryImpl,
        runtimeEvents: MutableSharedFlow<MediaCallEvent>,
    ) {
        repository.prepareCall(CallPreparationRequest.NewOutgoing("pair-1", true))
        repository.connectCall()
        runtimeEvents.emit(
            MediaCallEvent.Connected(
                callId = "call-1",
                generation = 1L,
                localIdentity = "self-1",
                remoteIdentity = "partner",
                remoteParticipantConnected = true,
            ),
        )
    }

    private fun interruptionRequest(
        sequence: Long,
        operationId: String = "system-call-operation",
    ) = SystemCallInterruptionRequest(
        callId = "call-1",
        operationId = operationId,
        telecomSequence = sequence,
    )

    private fun configureIdleRuntime() {
        every { audioRouteController.activeRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { audioRouteController.availableRoutes } returns MutableStateFlow(listOf(AudioRoute.Speaker))
        every { callServiceController.foregroundState } returns MutableStateFlow(ForegroundCallServiceState())
        every { callRuntimeController.mediaEvents } returns emptyFlow()
    }

    private fun sessionResponse(createdByRequest: Boolean) = SessionResponseDto(
        callId = "call-1",
        pairId = "pair-1",
        roomName = "room-1",
        participantIdentity = "self",
        token = "token",
        wsUrl = "wss://example.invalid",
        createdByRequest = createdByRequest,
    )
}

private class RecordingInterruptionTelemetry : CallInterruptionTelemetry {
    var attempts: Int = 0
        private set
    var finalFailures: Int = 0
        private set

    override fun recordTelecomCallback(
        callId: String,
        operationId: String,
        sequence: Long,
        callback: String,
        result: String,
        elapsedMillis: Long,
    ) = Unit

    override fun startTransition(
        context: CallInterruptionTransitionContext,
    ): CallInterruptionTelemetryOperation = object : CallInterruptionTelemetryOperation {
        override fun recordAttempt(
            attempt: Int,
            retriesRemaining: Int,
            result: String,
            reasonCode: String?,
        ) {
            attempts += 1
        }

        override fun finish(
            result: String,
            reasonCode: String?,
            throwable: Throwable?,
            finalFailure: Boolean,
        ) {
            if (finalFailure) finalFailures += 1
        }
    }
}
