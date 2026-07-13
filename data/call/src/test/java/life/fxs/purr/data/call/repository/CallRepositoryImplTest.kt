package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.SupervisorJob
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.media.service.ForegroundCallServiceState
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.core.network.model.SessionResponseDto
import life.fxs.purr.data.call.remote.CallStatusRemoteDataSource
import life.fxs.purr.data.call.remote.CallStatusSynchronizer
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.CallRuntimeController
import life.fxs.purr.data.call.state.CallMediaEventReducer
import life.fxs.purr.data.call.state.CallUiSnapshotAssembler
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.PrepareCallParams
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
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            callId = "call-1",
            pairId = "pair-1",
            roomName = "room-1",
            participantIdentity = "self",
            token = "token",
            wsUrl = "ws://example.invalid",
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

        val result = repository.prepareCall(PrepareCallParams(pairId = "pair-1", recordingConsent = true))
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

        repository.prepareCall(PrepareCallParams(pairId = "pair-1", recordingConsent = true))
        val preparedSession = repository.observeCallSession().first()
        assertThat(preparedSession?.timing?.synchronizedDurationMillis).isEqualTo(5_000L)
        assertThat(preparedSession?.timing?.isRunning).isTrue()
        val result = repository.disconnectCall()

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        coVerify(exactly = 1) { api.endCall("call-1") }
        coVerifyOrder {
            callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
            api.endCall("call-1")
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
        coEvery { api.createSession(any()) } returns SessionResponseDto(
            callId = "call-1",
            pairId = "pair-1",
            roomName = "room-1",
            participantIdentity = "self",
            token = "token",
            wsUrl = "ws://example.invalid",
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
        repository.prepareCall(PrepareCallParams(pairId = "pair-1", recordingConsent = true))

        val firstDisconnect = async { repository.disconnectCall() }
        serverEndStarted.await()
        val duplicateDisconnect = async { repository.disconnectCall(expectedCallId = "call-1") }

        val muteWhileServerWaits = repository.setMuted(muted = true)
        assertThat(muteWhileServerWaits).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.observeCallSession().first()?.connectionState)
            .isEqualTo(CallConnectionState.Disconnected)
        runCurrent()
        assertThat(firstDisconnect.isCompleted).isFalse()
        assertThat(duplicateDisconnect.isCompleted).isFalse()

        finishServerEnd.complete(Unit)
        assertThat(firstDisconnect.await()).isInstanceOf(AppResult.Success::class.java)
        assertThat(duplicateDisconnect.await()).isInstanceOf(AppResult.Success::class.java)
        coVerify(exactly = 1) { api.endCall("call-1") }
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
        )
        coEvery { callStatusRemoteDataSource.getStatus("call-1") } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "waiting",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus("call-1") } returns emptyFlow()
        val repository = repository()

        repository.prepareCall(PrepareCallParams(pairId = "pair-1", recordingConsent = true))
        repository.disconnectCall()
        val duplicateDisconnect = repository.disconnectCall(expectedCallId = "call-1")

        val result = repository.connectCall()

        assertThat(duplicateDisconnect).isInstanceOf(AppResult.Success::class.java)
        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        coVerify(exactly = 1) { api.endCall("call-1") }
        coVerify(exactly = 1) {
            callRuntimeController.execute(MediaCallCommand.Disconnect("call-1"))
        }
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
            SessionResponseDto("call-1", "pair-1", "room-1", "self-1", "token-1", "wss://one"),
            SessionResponseDto("call-2", "pair-1", "room-2", "self-2", "token-2", "wss://two"),
        )
        coEvery { callStatusRemoteDataSource.getStatus(any()) } returns CallStatusDto(
            callId = "call-1",
            pairId = "pair-1",
            state = "waiting",
            recordingStatus = "idle",
        )
        every { callStatusRemoteDataSource.observeStatus(any()) } returns emptyFlow()
        val repository = repository()

        val first = (repository.prepareCall(PrepareCallParams("pair-1", true)) as AppResult.Success).value
        repository.disconnectCall()
        repository.prepareCall(PrepareCallParams("pair-1", true))
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

    private fun repository() = CallRepositoryImpl(
        api = api,
        callRuntimeController = callRuntimeController,
        callStatusRemoteDataSource = callStatusRemoteDataSource,
        callStatusSynchronizer = CallStatusSynchronizer(callStatusRemoteDataSource, applicationScope),
        callMediaEventReducer = CallMediaEventReducer(),
        callUiSnapshotAssembler = CallUiSnapshotAssembler(audioRouteController, callServiceController),
        applicationScope = applicationScope,
    )
}
