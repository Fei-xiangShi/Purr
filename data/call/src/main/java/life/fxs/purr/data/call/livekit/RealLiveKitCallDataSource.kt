package life.fxs.purr.data.call.livekit

import io.livekit.android.ConnectOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent

/** LiveKit implementation of the transport-neutral [MediaCallPort] contract. */
@Singleton
class RealLiveKitCallDataSource @Inject constructor(
    private val roomFactory: LiveKitRoomFactory,
    private val roomStateProvider: MutableCallRoomStateProvider,
    private val audioLevelProvider: MutableCallAudioLevelProvider,
    private val logger: PurrLogger,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : LiveKitCallDataSource {
    private val scope = applicationScope
    private val eventBus = MutableSharedFlow<MediaCallEvent>(extraBufferCapacity = EVENT_BUFFER_SIZE)
    private val lifecycleMutex = Mutex()
    private val generationCounter = AtomicLong(0L)
    private val activeCall = AtomicReference<ActiveMediaCall?>(null)

    // A call id is single-use. Keep a bounded tombstone set so a stale client cannot
    // reconnect a completed call while avoiding unbounded process-local growth.
    private val terminatedCallIds = LinkedHashSet<String>()

    override val events: Flow<MediaCallEvent> = eventBus.asSharedFlow()

    @Volatile
    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var audioLevelTrack: LocalAudioTrack? = null
    private var remoteAudioLevelTrack: RemoteAudioTrack? = null

    override suspend fun execute(command: MediaCallCommand) {
        when (command) {
            is MediaCallCommand.Connect -> connect(command)
            is MediaCallCommand.Disconnect -> disconnect(command)
            is MediaCallCommand.SetMuted -> setMuted(command)
        }
    }

    private suspend fun connect(command: MediaCallCommand.Connect) = lifecycleMutex.withLock {
        if (command.callId in terminatedCallIds) {
            throw IllegalStateException("A completed call session cannot be connected again")
        }
        val existing = activeCall.get()
        if (existing != null) {
            if (existing.callId == command.callId) return@withLock
            throw IllegalStateException(
                "A different LiveKit call is already active: ${existing.callId}",
            )
        }
        if (room != null) {
            releaseRoomLocked()?.let { throw it }
            if (room != null) {
                throw IllegalStateException("LiveKit room cleanup did not complete")
            }
        }

        val generation = generationCounter.incrementAndGet()
        // Defensive cleanup handles a provider that left a released room reference behind.
        releaseRoomLocked()?.let { throw it }
        val mediaCall = ActiveMediaCall(
            callId = command.callId,
            generation = generation,
        )
        activeCall.set(mediaCall)

        try {
            val createdRoom = roomFactory.create()
            room = createdRoom
            roomStateProvider.update(createdRoom)
            observeRoomEvents(createdRoom, mediaCall)

            createdRoom.connect(
                url = command.connection.wsUrl,
                token = command.connection.accessToken,
                options = ConnectOptions(
                    audio = false,
                    video = false,
                ),
            )
            command.terminationSignal.throwIfRequested()

            if (!isCurrent(createdRoom, generation)) return@withLock

            val microphoneEnabled = createdRoom.localParticipant.setMicrophoneEnabled(true)
            if (!microphoneEnabled) throw IllegalStateException("Unable to publish microphone track")
            command.terminationSignal.throwIfRequested()
            attachLocalAudioLevel(createdRoom)
            attachRemoteAudioLevel(createdRoom)

            val localIdentity = createdRoom.localParticipant.identity?.value
                ?: throw IllegalStateException("LiveKit local identity is unavailable")
            eventBus.emit(
                MediaCallEvent.Connected(
                    callId = command.callId,
                    generation = generation,
                    localIdentity = localIdentity,
                    remoteIdentity = createdRoom.remoteParticipants.keys.firstOrNull()?.value,
                    remoteParticipantConnected = createdRoom.remoteParticipants.isNotEmpty(),
                ),
            )
        } catch (throwable: Throwable) {
            val shouldPublishFailure = isActiveGeneration(command.callId, generation)
            if (shouldPublishFailure) {
                activeCall.set(null)
                rememberTerminatedCall(command.callId)
                generationCounter.incrementAndGet()
                val cleanupFailure = releaseRoomLocked()
                cleanupFailure?.let(throwable::addSuppressed)
                eventBus.emit(
                    MediaCallEvent.Failed(
                        callId = command.callId,
                        generation = generation,
                        reason = throwable.message,
                    ),
                )
            }
            throw throwable
        }
    }

    private suspend fun disconnect(command: MediaCallCommand.Disconnect) = lifecycleMutex.withLock {
        val current = activeCall.get() ?: return@withLock
        if (current.callId != command.callId) return@withLock
        activeCall.set(null)
        rememberTerminatedCall(current.callId)
        generationCounter.incrementAndGet()
        releaseRoomLocked()?.let { throw it }
    }

    private suspend fun setMuted(command: MediaCallCommand.SetMuted) = lifecycleMutex.withLock {
        val current = activeCall.get() ?: return@withLock
        if (current.callId != command.callId) return@withLock
        val activeRoom = room ?: return@withLock
        val changed = activeRoom.localParticipant.setMicrophoneEnabled(!command.muted)
        if (!changed) return@withLock
        if (command.muted) {
            detachLocalAudioLevel()
        } else {
            attachLocalAudioLevel(activeRoom)
        }
        if (isCurrent(activeRoom, current.generation)) {
            eventBus.emit(
                MediaCallEvent.AudioStateChanged(
                    callId = current.callId,
                    generation = current.generation,
                    muted = command.muted,
                ),
            )
        }
    }

    private fun observeRoomEvents(activeRoom: Room, mediaCall: ActiveMediaCall) {
        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            activeRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        attachRemoteAudioLevel(activeRoom)
                        publishParticipantChanged(activeRoom, mediaCall)
                    }

                    // LiveKit owns the retry window. Reconnecting is a transient transport
                    // state and must not be promoted to a business-level hang-up.
                    is RoomEvent.Reconnecting -> publishReconnecting(activeRoom, mediaCall)

                    is RoomEvent.Reconnected -> {
                        attachLocalAudioLevel(activeRoom)
                        attachRemoteAudioLevel(activeRoom)
                        publishReconnected(activeRoom, mediaCall)
                    }

                    is RoomEvent.Disconnected -> terminateCurrentCall(
                        activeRoom = activeRoom,
                        mediaCall = mediaCall,
                        failure = event.error,
                    )

                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    -> {
                        attachRemoteAudioLevel(activeRoom)
                        publishParticipantChanged(activeRoom, mediaCall)
                    }

                    is RoomEvent.TrackSubscribed -> {
                        (event.track as? RemoteAudioTrack)?.let(::attachRemoteAudioLevel)
                    }

                    is RoomEvent.TrackUnsubscribed -> {
                        if (event.track === remoteAudioLevelTrack) {
                            detachRemoteAudioLevel()
                            attachRemoteAudioLevel(activeRoom)
                        }
                    }

                    is RoomEvent.ConnectionQualityChanged -> {
                        if (event.participant.identity == activeRoom.localParticipant.identity) {
                            publishNetworkQuality(activeRoom, mediaCall, event.quality)
                        }
                    }

                    is RoomEvent.FailedToConnect -> terminateCurrentCall(
                        activeRoom = activeRoom,
                        mediaCall = mediaCall,
                        failure = event.error,
                    )

                    is RoomEvent.RecordingStatusChanged -> Unit
                    else -> Unit
                }
            }
        }
    }

    private suspend fun publishReconnecting(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation)) return@withLock
            eventBus.emit(
                MediaCallEvent.Reconnecting(
                    callId = mediaCall.callId,
                    generation = mediaCall.generation,
                ),
            )
        }
    }

    private suspend fun publishReconnected(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation)) return@withLock
            eventBus.emit(
                MediaCallEvent.Reconnected(
                    callId = mediaCall.callId,
                    generation = mediaCall.generation,
                    remoteIdentity = activeRoom.remoteParticipants.keys.firstOrNull()?.value,
                    remoteParticipantConnected = activeRoom.remoteParticipants.isNotEmpty(),
                ),
            )
        }
    }

    private suspend fun publishParticipantChanged(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation)) return@withLock
            eventBus.emit(
                MediaCallEvent.ParticipantChanged(
                    callId = mediaCall.callId,
                    generation = mediaCall.generation,
                    remoteIdentity = activeRoom.remoteParticipants.keys.firstOrNull()?.value,
                    remoteParticipantConnected = activeRoom.remoteParticipants.isNotEmpty(),
                ),
            )
        }
    }

    private suspend fun publishNetworkQuality(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
        quality: ConnectionQuality,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation)) return@withLock
            val score = quality.toScore()
            eventBus.emit(
                MediaCallEvent.NetworkQualityChanged(
                    callId = mediaCall.callId,
                    generation = mediaCall.generation,
                    uplinkScore = score,
                    downlinkScore = score,
                    sampledAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun terminateCurrentCall(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
        failure: Throwable? = null,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation)) return@withLock
            if (failure == null) {
                eventBus.emit(
                    MediaCallEvent.Disconnected(
                        callId = mediaCall.callId,
                        generation = mediaCall.generation,
                    ),
                )
            } else {
                eventBus.emit(
                    MediaCallEvent.Failed(
                        callId = mediaCall.callId,
                        generation = mediaCall.generation,
                        reason = failure.message,
                    ),
                )
            }
            activeCall.set(null)
            rememberTerminatedCall(mediaCall.callId)
            generationCounter.incrementAndGet()
            releaseRoomLocked()?.let { cleanupFailure ->
                failure?.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun attachLocalAudioLevel(activeRoom: Room) {
        val publication = activeRoom.localParticipant
            .getTrackPublication(Track.Source.MICROPHONE)
        val microphoneTrack = publication?.track as? LocalAudioTrack
        if (publication == null || publication.muted || microphoneTrack == null) {
            detachLocalAudioLevel()
            return
        }
        if (audioLevelTrack === microphoneTrack) return
        detachLocalAudioLevel()
        runCatching { microphoneTrack.addSink(audioLevelProvider) }
            .onSuccess { audioLevelTrack = microphoneTrack }
            .onFailure { audioLevelProvider.reset() }
    }

    private fun detachLocalAudioLevel() {
        val track = audioLevelTrack
        audioLevelTrack = null
        runCatching { track?.removeSink(audioLevelProvider) }
        audioLevelProvider.reset()
    }

    private fun attachRemoteAudioLevel(activeRoom: Room) {
        val remoteTrack = activeRoom.remoteParticipants.values
            .asSequence()
            .mapNotNull { participant ->
                participant.audioTrackPublications
                    .firstOrNull { (publication, _) -> publication.source == Track.Source.MICROPHONE }
                    ?.second as? RemoteAudioTrack
            }
            .firstOrNull()
        if (remoteTrack == null) {
            detachRemoteAudioLevel()
        } else {
            attachRemoteAudioLevel(remoteTrack)
        }
    }

    private fun attachRemoteAudioLevel(remoteTrack: RemoteAudioTrack) {
        if (remoteAudioLevelTrack === remoteTrack) return
        detachRemoteAudioLevel()
        runCatching { remoteTrack.addSink(audioLevelProvider.remoteAudioSink) }
            .onSuccess { remoteAudioLevelTrack = remoteTrack }
            .onFailure { audioLevelProvider.resetRemote() }
    }

    private fun detachRemoteAudioLevel() {
        val track = remoteAudioLevelTrack
        remoteAudioLevelTrack = null
        runCatching { track?.removeSink(audioLevelProvider.remoteAudioSink) }
        audioLevelProvider.resetRemote()
    }

    private fun isCurrent(activeRoom: Room?, generation: Long): Boolean {
        val current = activeCall.get()
        return activeRoom != null && room === activeRoom && current?.generation == generation
    }

    private fun isActiveGeneration(callId: String, generation: Long): Boolean {
        val current = activeCall.get()
        return current?.callId == callId && current.generation == generation
    }

    private fun rememberTerminatedCall(callId: String) {
        terminatedCallIds += callId
        while (terminatedCallIds.size > MAX_TERMINATED_CALL_IDS) {
            terminatedCallIds.remove(terminatedCallIds.first())
        }
    }

    /** Releases all process-local room resources. Callers must hold [lifecycleMutex]. */
    private fun releaseRoomLocked(): Throwable? {
        roomEventsJob?.cancel()
        roomEventsJob = null
        var failure: Throwable? = null
        detachLocalAudioLevel()
        detachRemoteAudioLevel()
        val activeRoom = room
        val disconnectStartedAt = System.nanoTime()
        logger.d(LOG_TAG, "phase=livekit.disconnect event=begin")
        try {
            activeRoom?.disconnect()
            logger.d(LOG_TAG, "phase=livekit.disconnect event=end elapsedMs=${elapsedMillis(disconnectStartedAt)}")
        } catch (throwable: Throwable) {
            logger.e(
                LOG_TAG,
                throwable,
                "phase=livekit.disconnect event=error elapsedMs=${elapsedMillis(disconnectStartedAt)}",
            )
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        val releaseStartedAt = System.nanoTime()
        logger.d(LOG_TAG, "phase=livekit.release event=begin")
        try {
            activeRoom?.release()
            logger.d(LOG_TAG, "phase=livekit.release event=end elapsedMs=${elapsedMillis(releaseStartedAt)}")
        } catch (throwable: Throwable) {
            logger.e(
                LOG_TAG,
                throwable,
                "phase=livekit.release event=error elapsedMs=${elapsedMillis(releaseStartedAt)}",
            )
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        room = null
        roomStateProvider.update(null)
        return failure
    }

    private data class ActiveMediaCall(
        val callId: String,
        val generation: Long,
    )

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        const val LOG_TAG = "CallLiveKit"
        const val EVENT_BUFFER_SIZE = 64
        const val MAX_TERMINATED_CALL_IDS = 128
    }
}

private fun ConnectionQuality.toScore(): Int = when (this) {
    ConnectionQuality.EXCELLENT -> 5
    ConnectionQuality.GOOD -> 4
    ConnectionQuality.POOR -> 2
    ConnectionQuality.LOST -> 1
    ConnectionQuality.UNKNOWN -> 3
}
