package life.fxs.purr.data.call.livekit

import com.google.common.truth.Truth.assertThat
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.runtime.CallMediaConnection
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent

@OptIn(ExperimentalCoroutinesApi::class)
class RealLiveKitCallDataSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val roomFactory = mockk<LiveKitRoomFactory>()
    private val roomStateProvider = MutableCallRoomStateProvider()
    private val audioLevelProvider = MutableCallAudioLevelProvider()
    private lateinit var applicationScope: CoroutineScope

    @Before
    fun setUp() {
        applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun `disconnected event releases room and sampling resources exactly once`() = runTest(dispatcher) {
        val harness = roomHarness()
        every { roomFactory.create() } returns harness.room
        val source = source()
        val observed = mutableListOf<MediaCallEvent>()
        val observer = launch { source.events.toList(observed) }

        source.execute(connectCommand())
        runCurrent()
        harness.events.emit(
            RoomEvent.Disconnected(
                room = harness.room,
                error = null,
                reason = DisconnectReason.SERVER_SHUTDOWN,
            ),
        )
        runCurrent()

        assertThat(observed.filterIsInstance<MediaCallEvent.Disconnected>()).hasSize(1)
        assertThat(roomStateProvider.room.value).isNull()
        assertThat(audioLevelProvider.localAudioLevel.value).isEqualTo(0f)
        verify(exactly = 1) { harness.localAudioTrack.addSink(audioLevelProvider) }
        verify(exactly = 1) { harness.localAudioTrack.removeSink(audioLevelProvider) }
        verify(exactly = 1) { harness.room.disconnect() }
        verify(exactly = 1) { harness.room.release() }

        harness.events.emit(
            RoomEvent.Disconnected(
                room = harness.room,
                error = null,
                reason = DisconnectReason.SERVER_SHUTDOWN,
            ),
        )
        runCurrent()
        verify(exactly = 1) { harness.room.disconnect() }
        verify(exactly = 1) { harness.room.release() }
        observer.cancel()
    }

    @Test
    fun `muting detaches pcm sink and unmuting reattaches it`() = runTest(dispatcher) {
        val harness = roomHarness()
        every { roomFactory.create() } returns harness.room
        val source = source()

        source.execute(connectCommand())
        source.execute(MediaCallCommand.SetMuted(callId = "call-1", muted = true))

        assertThat(audioLevelProvider.localAudioLevel.value).isEqualTo(0f)
        verify(exactly = 1) { harness.localAudioTrack.removeSink(audioLevelProvider) }

        source.execute(MediaCallCommand.SetMuted(callId = "call-1", muted = false))

        verify(exactly = 2) { harness.localAudioTrack.addSink(audioLevelProvider) }
    }

    @Test
    fun `pcm meter failure cannot fail the media call`() = runTest(dispatcher) {
        val harness = roomHarness()
        every { roomFactory.create() } returns harness.room
        every { harness.localAudioTrack.addSink(audioLevelProvider) } throws
            IllegalStateException("meter unavailable")
        val source = source()
        val observed = mutableListOf<MediaCallEvent>()
        val observer = launch { source.events.toList(observed) }
        runCurrent()

        val result = runCatching { source.execute(connectCommand()) }
        runCurrent()

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(observed.filterIsInstance<MediaCallEvent.Connected>()).hasSize(1)
        observer.cancel()
    }

    @Test
    fun `failed to connect event publishes failure and releases room`() = runTest(dispatcher) {
        val harness = roomHarness()
        every { roomFactory.create() } returns harness.room
        val source = source()
        val observed = mutableListOf<MediaCallEvent>()
        val observer = launch { source.events.toList(observed) }

        source.execute(connectCommand())
        runCurrent()
        harness.events.emit(
            RoomEvent.FailedToConnect(
                room = harness.room,
                error = IllegalStateException("provider failed"),
            ),
        )
        runCurrent()

        val failure = observed.filterIsInstance<MediaCallEvent.Failed>().single()
        assertThat(failure.reason).isEqualTo("provider failed")
        assertThat(roomStateProvider.room.value).isNull()
        verify(exactly = 1) { harness.room.disconnect() }
        verify(exactly = 1) { harness.room.release() }
        observer.cancel()
    }

    @Test
    fun `disconnect waits for in flight connect then releases the same room`() = runTest(dispatcher) {
        val connectStarted = CompletableDeferred<Unit>()
        val allowConnect = CompletableDeferred<Unit>()
        val harness = roomHarness(connectStarted, allowConnect)
        every { roomFactory.create() } returns harness.room
        val source = source()

        val connectJob = launch { source.execute(connectCommand()) }
        runCurrent()
        connectStarted.await()
        val disconnectJob = launch {
            source.execute(MediaCallCommand.Disconnect(callId = "call-1"))
        }
        runCurrent()

        assertThat(disconnectJob.isCompleted).isFalse()
        allowConnect.complete(Unit)
        joinAll(connectJob, disconnectJob)

        assertThat(roomStateProvider.room.value).isNull()
        verify(exactly = 1) { harness.room.disconnect() }
        verify(exactly = 1) { harness.room.release() }
    }

    @Test
    fun `terminated call id cannot connect again`() = runTest(dispatcher) {
        val harness = roomHarness()
        every { roomFactory.create() } returns harness.room
        val source = source()

        source.execute(connectCommand())
        source.execute(MediaCallCommand.Disconnect(callId = "call-1"))

        val result = runCatching { source.execute(connectCommand()) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `room creation failure publishes terminal event and forbids retry`() = runTest(dispatcher) {
        every { roomFactory.create() } throws IllegalStateException("room creation failed")
        val source = source()
        val observed = mutableListOf<MediaCallEvent>()
        val observer = launch { source.events.toList(observed) }
        runCurrent()

        val firstAttempt = runCatching { source.execute(connectCommand()) }
        runCurrent()
        val secondAttempt = runCatching { source.execute(connectCommand()) }

        assertThat(firstAttempt.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(observed.filterIsInstance<MediaCallEvent.Failed>().single().reason)
            .isEqualTo("room creation failed")
        assertThat(secondAttempt.exceptionOrNull()?.message)
            .contains("completed call session")
        observer.cancel()
    }

    private fun source() = RealLiveKitCallDataSource(
        roomFactory = roomFactory,
        roomStateProvider = roomStateProvider,
        audioLevelProvider = audioLevelProvider,
        applicationScope = applicationScope,
    )

    private fun connectCommand() = MediaCallCommand.Connect(
        callId = "call-1",
        pairId = "pair-1",
        localIdentity = "pending",
        connection = CallMediaConnection(
            wsUrl = "wss://example.invalid",
            accessToken = "token",
        ),
    )

    private fun roomHarness(
        connectStarted: CompletableDeferred<Unit>? = null,
        allowConnect: CompletableDeferred<Unit>? = null,
    ): RoomHarness {
        val events = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 4)
        val eventListenable = mockk<EventListenable<RoomEvent>>()
        val room = mockk<Room>()
        val localParticipant = mockk<LocalParticipant>()
        val microphonePublication = mockk<LocalTrackPublication>()
        val localAudioTrack = mockk<LocalAudioTrack>(relaxed = true)
        every { eventListenable.events } returns events
        every { room.events } returns eventListenable
        every { room.localParticipant } returns localParticipant
        every { room.remoteParticipants } returns emptyMap()
        every { localParticipant.identity } returns null
        every { localParticipant.getTrackPublication(Track.Source.MICROPHONE) } returns microphonePublication
        every { microphonePublication.track } returns localAudioTrack
        coEvery { localParticipant.setMicrophoneEnabled(any()) } coAnswers {
            connectStarted?.complete(Unit)
            allowConnect?.await()
            true
        }
        coEvery { room.connect(any(), any(), any()) } returns null
        every { room.disconnect() } returns Unit
        every { room.release() } returns Unit
        return RoomHarness(room, events, localAudioTrack)
    }
}

private data class RoomHarness(
    val room: Room,
    val events: MutableSharedFlow<RoomEvent>,
    val localAudioTrack: LocalAudioTrack,
)
