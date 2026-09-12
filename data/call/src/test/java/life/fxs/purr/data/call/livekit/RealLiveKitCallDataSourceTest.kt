package life.fxs.purr.data.call.livekit

import com.google.common.truth.Truth.assertThat
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
import life.fxs.purr.core.common.NoOpPurrLogger
import life.fxs.purr.core.model.SystemCallInterruptionPhase
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.runtime.CallMediaConnection
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.MediaSystemCallInterruptionResult
import life.fxs.purr.data.call.runtime.MediaSystemCallResumeRequest
import life.fxs.purr.data.call.runtime.MediaSystemCallSuspendRequest

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
    fun `reconnecting keeps the active room and publishes recovery facts`() = runTest(dispatcher) {
        val harness = roomHarness()
        every { roomFactory.create() } returns harness.room
        val source = source()
        val observed = mutableListOf<MediaCallEvent>()
        val observer = launch { source.events.toList(observed) }

        source.execute(connectCommand())
        runCurrent()
        harness.events.emit(RoomEvent.Reconnecting(harness.room))
        runCurrent()

        assertThat(observed.filterIsInstance<MediaCallEvent.Reconnecting>()).hasSize(1)
        assertThat(observed.filterIsInstance<MediaCallEvent.Disconnected>()).isEmpty()
        assertThat(roomStateProvider.room.value).isSameInstanceAs(harness.room)
        verify(exactly = 0) { harness.room.disconnect() }
        verify(exactly = 0) { harness.room.release() }

        harness.events.emit(RoomEvent.Reconnected(harness.room))
        runCurrent()

        assertThat(observed.filterIsInstance<MediaCallEvent.Reconnected>()).hasSize(1)
        assertThat(roomStateProvider.room.value).isSameInstanceAs(harness.room)
        verify(exactly = 0) { harness.room.disconnect() }
        verify(exactly = 0) { harness.room.release() }
        observer.cancel()
    }

    @Test
    fun `reconnected rebinds audio sinks when track wrappers are unchanged`() = runTest(dispatcher) {
        val remoteTrack = mockk<RemoteAudioTrack>(relaxed = true)
        val harness = roomHarness(existingRemoteTrack = remoteTrack)
        every { roomFactory.create() } returns harness.room
        val source = source()

        source.execute(connectCommand())
        runCurrent()
        verify(exactly = 1) { harness.localAudioTrack.addSink(audioLevelProvider) }
        verify(exactly = 1) { remoteTrack.addSink(audioLevelProvider.remoteAudioSink) }

        harness.events.emit(RoomEvent.Reconnecting(harness.room))
        harness.events.emit(RoomEvent.Reconnected(harness.room))
        runCurrent()

        verify(exactly = 1) { harness.localAudioTrack.removeSink(audioLevelProvider) }
        verify(exactly = 2) { harness.localAudioTrack.addSink(audioLevelProvider) }
        verify(exactly = 1) { remoteTrack.removeSink(audioLevelProvider.remoteAudioSink) }
        verify(exactly = 2) { remoteTrack.addSink(audioLevelProvider.remoteAudioSink) }
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

        harness.events.emit(RoomEvent.Reconnecting(harness.room))
        harness.events.emit(RoomEvent.Reconnected(harness.room))
        runCurrent()

        verify(exactly = 1) { harness.localAudioTrack.addSink(audioLevelProvider) }

        source.execute(MediaCallCommand.SetMuted(callId = "call-1", muted = false))

        verify(exactly = 2) { harness.localAudioTrack.addSink(audioLevelProvider) }
    }

    @Test
    fun `subscribed remote microphone uses pcm sink until call release`() = runTest(dispatcher) {
        val harness = roomHarness()
        val remoteTrack = mockk<RemoteAudioTrack>(relaxed = true)
        val remotePublication = mockk<RemoteTrackPublication>()
        val remoteParticipant = mockk<RemoteParticipant>()
        every { roomFactory.create() } returns harness.room
        val source = source()

        source.execute(connectCommand())
        runCurrent()
        harness.events.emit(
            RoomEvent.TrackSubscribed(
                room = harness.room,
                track = remoteTrack,
                publication = remotePublication,
                participant = remoteParticipant,
            ),
        )
        runCurrent()

        verify(exactly = 1) { remoteTrack.addSink(audioLevelProvider.remoteAudioSink) }

        source.execute(MediaCallCommand.Disconnect(callId = "call-1"))

        verify(exactly = 1) { remoteTrack.removeSink(audioLevelProvider.remoteAudioSink) }
        assertThat(audioLevelProvider.remoteAudioLevel.value).isEqualTo(0f)
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
        verify(exactly = 1, timeout = 2_000) { harness.room.disconnect() }
        verify(exactly = 1, timeout = 2_000) { harness.room.release() }
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
    fun `same business call can start a new media generation after disconnect`() = runTest(dispatcher) {
        val first = roomHarness()
        val second = roomHarness()
        every { roomFactory.create() } returnsMany listOf(first.room, second.room)
        val source = source()

        source.execute(connectCommand())
        source.execute(MediaCallCommand.Disconnect(callId = "call-1"))
        val result = runCatching { source.execute(connectCommand()) }

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(roomStateProvider.room.value).isSameInstanceAs(second.room)
        verify(exactly = 2) { roomFactory.create() }
        verify(exactly = 0) { second.room.disconnect() }
    }

    @Test
    fun `old same-id generation event cannot terminate a newer media attempt`() = runTest(dispatcher) {
        val first = roomHarness()
        val second = roomHarness()
        every { roomFactory.create() } returnsMany listOf(first.room, second.room)
        val source = source()
        val observed = mutableListOf<MediaCallEvent>()
        val observer = launch { source.events.toList(observed) }
        runCurrent()

        source.execute(connectCommand())
        source.execute(MediaCallCommand.Disconnect(callId = "call-1"))
        source.execute(connectCommand())
        runCurrent()
        first.events.emit(
            RoomEvent.FailedToConnect(
                room = first.room,
                error = IllegalStateException("stale failure"),
            ),
        )
        runCurrent()

        assertThat(observed.filterIsInstance<MediaCallEvent.Failed>()).isEmpty()
        assertThat(roomStateProvider.room.value).isSameInstanceAs(second.room)
        verify(exactly = 0) { second.room.disconnect() }
        observer.cancel()
    }

    @Test
    fun `room creation failure publishes terminal event but a later explicit attempt may connect`() =
        runTest(dispatcher) {
            val recovered = roomHarness()
            var attempts = 0
            every { roomFactory.create() } answers {
                if (attempts++ == 0) throw IllegalStateException("room creation failed")
                recovered.room
            }
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
            assertThat(secondAttempt.exceptionOrNull()).isNull()
            assertThat(roomStateProvider.room.value).isSameInstanceAs(recovered.room)
            observer.cancel()
        }

    @Test
    fun `system suspension closes existing and newly subscribed audio before resume`() = runTest(dispatcher) {
        val existingRemoteTrack = mockk<RemoteAudioTrack>(relaxed = true)
        val newlySubscribedTrack = mockk<RemoteAudioTrack>(relaxed = true)
        val harness = roomHarness(existingRemoteTrack = existingRemoteTrack)
        every { roomFactory.create() } returns harness.room
        val source = source()
        source.execute(connectCommand())
        runCurrent()

        val suspended = source.suspendForSystemCall(
            MediaSystemCallSuspendRequest(
                callId = "call-1",
                expectedGeneration = 1L,
                operationId = "operation-1",
            ),
        )
        harness.events.emit(
            RoomEvent.TrackSubscribed(
                room = harness.room,
                track = newlySubscribedTrack,
                publication = mockk(),
                participant = mockk(),
            ),
        )
        runCurrent()

        assertThat(suspended).isEqualTo(MediaSystemCallInterruptionResult.Applied(1L))
        coVerifyOrder {
            harness.localParticipant.setMicrophoneEnabled(true)
            harness.localParticipant.setMicrophoneEnabled(false)
        }
        verify { existingRemoteTrack.setVolume(0.0) }
        verify { newlySubscribedTrack.setVolume(0.0) }

        val resumed = source.resumeAfterSystemCall(
            MediaSystemCallResumeRequest(
                callId = "call-1",
                expectedGeneration = 1L,
                operationId = "operation-1",
                enableMicrophone = true,
            ),
        )

        assertThat(resumed).isEqualTo(MediaSystemCallInterruptionResult.Applied(1L))
        coVerify(exactly = 2) { harness.localParticipant.setMicrophoneEnabled(true) }
        verify(atLeast = 2) { existingRemoteTrack.setVolume(1.0) }
    }

    @Test
    fun `resume preserves user muted microphone intent`() = runTest(dispatcher) {
        val remoteTrack = mockk<RemoteAudioTrack>(relaxed = true)
        val harness = roomHarness(existingRemoteTrack = remoteTrack)
        every { roomFactory.create() } returns harness.room
        val source = source()
        source.execute(connectCommand())
        source.execute(MediaCallCommand.SetMuted(callId = "call-1", muted = true))
        source.suspendForSystemCall(
            MediaSystemCallSuspendRequest("call-1", 1L, "operation-1"),
        )

        val result = source.resumeAfterSystemCall(
            MediaSystemCallResumeRequest(
                callId = "call-1",
                expectedGeneration = 1L,
                operationId = "operation-1",
                enableMicrophone = false,
            ),
        )

        assertThat(result).isEqualTo(MediaSystemCallInterruptionResult.Applied(1L))
        coVerify(exactly = 1) { harness.localParticipant.setMicrophoneEnabled(true) }
        coVerify(exactly = 3) { harness.localParticipant.setMicrophoneEnabled(false) }
        verify(atLeast = 2) { remoteTrack.setVolume(1.0) }
    }

    @Test
    fun `reconnected forces interruption attribute resend after snapshot confirmation`() =
        runTest(dispatcher) {
            val harness = roomHarness()
            every { roomFactory.create() } returns harness.room
            val source = source()
            source.execute(connectCommand())
            runCurrent()
            source.suspendForSystemCall(
                MediaSystemCallSuspendRequest("call-1", 1L, "operation-1"),
            )
            runCurrent()

            coVerify(exactly = 1) { harness.localParticipant.updateAttributes(any()) }

            harness.events.emit(RoomEvent.Reconnected(harness.room))
            runCurrent()

            coVerify(exactly = 2) { harness.localParticipant.updateAttributes(any()) }
        }

    @Test
    fun `remote interruption snapshot replays and rejects stale sequence without blocking reconnect`() =
        runTest(dispatcher) {
            val remoteTrack = mockk<RemoteAudioTrack>(relaxed = true)
            val harness = roomHarness(existingRemoteTrack = remoteTrack)
            every { roomFactory.create() } returns harness.room
            val source = source()
            val observed = mutableListOf<MediaCallEvent>()
            val observer = launch { source.events.toList(observed) }
            runCurrent()
            source.execute(connectCommand())
            runCurrent()

            harness.remoteAttributes[SystemCallInterruptionWireCodec.ATTRIBUTE_KEY] =
                remoteWireState(sequence = 2L, phase = SystemCallInterruptionPhase.Suspended)
            harness.events.emit(RoomEvent.Reconnected(harness.room))
            runCurrent()

            val suspended = observed.filterIsInstance<MediaCallEvent.RemoteSystemCallInterruptionChanged>()
                .single()
            assertThat(suspended.operationId).isEqualTo("remote-operation")
            assertThat(suspended.phase).isEqualTo(SystemCallInterruptionPhase.Suspended)

            harness.remoteAttributes[SystemCallInterruptionWireCodec.ATTRIBUTE_KEY] =
                remoteWireState(sequence = 1L, phase = SystemCallInterruptionPhase.Active)
            harness.events.emit(RoomEvent.Reconnected(harness.room))
            runCurrent()
            assertThat(observed.filterIsInstance<MediaCallEvent.RemoteSystemCallInterruptionChanged>())
                .hasSize(1)

            harness.remoteAttributes[SystemCallInterruptionWireCodec.ATTRIBUTE_KEY] = "invalid-json"
            harness.events.emit(RoomEvent.Reconnected(harness.room))
            runCurrent()

            assertThat(observed.filterIsInstance<MediaCallEvent.Reconnected>()).hasSize(3)
            assertThat(observed.filterIsInstance<MediaCallEvent.RemoteSystemCallInterruptionChanged>())
                .hasSize(1)
            observer.cancel()
        }

    @Test
    fun `resume failure rolls every medium back to suspended`() = runTest(dispatcher) {
        val remoteTrack = mockk<RemoteAudioTrack>(relaxed = true)
        var restoreFailurePending = true
        every { remoteTrack.setVolume(1.0) } answers {
            if (restoreFailurePending) {
                restoreFailurePending = false
                throw IllegalStateException("speaker restore failed")
            }
        }
        val harness = roomHarness(existingRemoteTrack = remoteTrack)
        every { roomFactory.create() } returns harness.room
        val source = source()
        source.execute(connectCommand())
        source.suspendForSystemCall(
            MediaSystemCallSuspendRequest("call-1", 1L, "operation-1"),
        )

        val result = source.resumeAfterSystemCall(
            MediaSystemCallResumeRequest("call-1", 1L, "operation-1", enableMicrophone = true),
        )

        assertThat(result).isInstanceOf(MediaSystemCallInterruptionResult.Failed::class.java)
        coVerify(atLeast = 2) { harness.localParticipant.setMicrophoneEnabled(false) }
        verify(atLeast = 2) { remoteTrack.setVolume(0.0) }
    }

    private fun source() = RealLiveKitCallDataSource(
        roomFactory = roomFactory,
        roomStateProvider = roomStateProvider,
        audioLevelProvider = audioLevelProvider,
        logger = NoOpPurrLogger,
        applicationScope = applicationScope,
    )

    private fun connectCommand() = MediaCallCommand.Connect(
        callId = "call-1",
        pairId = "pair-1",
        localIdentity = "pending",
        remoteDisplayName = "Partner",
        direction = life.fxs.purr.core.model.CallDirection.Outgoing,
        connection = CallMediaConnection(
            wsUrl = "wss://example.invalid",
            accessToken = "token",
        ),
    )

    private fun remoteWireState(
        sequence: Long,
        phase: SystemCallInterruptionPhase,
    ): String = SystemCallInterruptionWireCodec.encode(
        SystemCallInterruptionWireState(
            v = SystemCallInterruptionWireCodec.SCHEMA_VERSION,
            callId = "call-1",
            senderGeneration = 7L,
            senderSessionId = "remote-session",
            sequence = sequence,
            operationId = "remote-operation",
            phase = phase.toWireValue(),
            degraded = false,
        ),
    )

    private fun roomHarness(
        connectStarted: CompletableDeferred<Unit>? = null,
        allowConnect: CompletableDeferred<Unit>? = null,
        existingRemoteTrack: RemoteAudioTrack? = null,
    ): RoomHarness {
        val events = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 4)
        val eventListenable = mockk<EventListenable<RoomEvent>>()
        val room = mockk<Room>()
        val localParticipant = mockk<LocalParticipant>()
        val microphonePublication = mockk<LocalTrackPublication>()
        val localAudioTrack = mockk<LocalAudioTrack>(relaxed = true)
        val remoteParticipant = mockk<RemoteParticipant>()
        val remotePublication = mockk<RemoteTrackPublication>()
        val remoteIdentity = mockk<io.livekit.android.room.participant.Participant.Identity>(relaxed = true)
        val remoteSid = Participant.Sid("remote-sid")
        val attributes = mutableMapOf<String, String>()
        val remoteAttributes = mutableMapOf<String, String>()
        var microphoneMuted = false
        var roomState = Room.State.CONNECTED
        every { eventListenable.events } returns events
        every { room.events } returns eventListenable
        every { room.state } answers { roomState }
        every { room.localParticipant } returns localParticipant
        every { room.remoteParticipants } returns if (existingRemoteTrack == null) {
            emptyMap()
        } else {
            mapOf(remoteIdentity to remoteParticipant)
        }
        every { localParticipant.identity } returns null
        every { localParticipant.identity?.value } returns "resolved-self"
        every { localParticipant.attributes } returns attributes
        every { localParticipant.getTrackPublication(Track.Source.MICROPHONE) } returns microphonePublication
        every { microphonePublication.muted } answers { microphoneMuted }
        every { microphonePublication.track } returns localAudioTrack
        every { remotePublication.source } returns Track.Source.MICROPHONE
        every { remoteParticipant.identity } returns remoteIdentity
        every { remoteParticipant.sid } returns remoteSid
        every { remoteParticipant.attributes } returns remoteAttributes
        every { remoteParticipant.audioTrackPublications } returns if (existingRemoteTrack == null) {
            emptyList()
        } else {
            listOf(remotePublication to existingRemoteTrack)
        }
        coEvery { localParticipant.updateAttributes(any()) } coAnswers {
            attributes.putAll(firstArg())
        }
        coEvery { localParticipant.setMicrophoneEnabled(any()) } coAnswers {
            microphoneMuted = !firstArg<Boolean>()
            connectStarted?.complete(Unit)
            allowConnect?.await()
            true
        }
        coEvery { room.connect(any(), any(), any()) } returns null
        every { room.disconnect() } returns Unit
        every { room.release() } returns Unit
        return RoomHarness(
            room = room,
            events = events,
            localParticipant = localParticipant,
            localAudioTrack = localAudioTrack,
            remoteAttributes = remoteAttributes,
            setRoomState = { roomState = it },
        )
    }
}

private data class RoomHarness(
    val room: Room,
    val events: MutableSharedFlow<RoomEvent>,
    val localParticipant: LocalParticipant,
    val localAudioTrack: LocalAudioTrack,
    val remoteAttributes: MutableMap<String, String>,
    val setRoomState: (Room.State) -> Unit,
)
