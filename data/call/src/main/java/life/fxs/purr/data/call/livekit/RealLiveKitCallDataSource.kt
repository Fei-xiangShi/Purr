package life.fxs.purr.data.call.livekit

import io.livekit.android.ConnectOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.runtime.MediaCallCommand
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.MediaSystemCallInterruptionResult
import life.fxs.purr.data.call.runtime.MediaSystemCallResumeRequest
import life.fxs.purr.data.call.runtime.MediaSystemCallSuspendRequest
import life.fxs.purr.core.model.SystemCallInterruptionPhase

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

    override suspend fun suspendForSystemCall(
        request: MediaSystemCallSuspendRequest,
    ): MediaSystemCallInterruptionResult = lifecycleMutex.withLock {
        val current = activeCall.get()
            ?: return@withLock MediaSystemCallInterruptionResult.Stale(
                generation = null,
                reasonCode = "no_active_media_call",
            )
        val activeRoom = room
        if (
            current.callId != request.callId ||
            current.generation != request.expectedGeneration ||
            activeRoom == null ||
            !isCurrent(activeRoom, current.generation)
        ) {
            return@withLock MediaSystemCallInterruptionResult.Stale(
                generation = current.generation,
                reasonCode = "media_generation_mismatch",
            )
        }

        current.systemSuspended = true
        current.interruptionOperationId = request.operationId
        val failure = enforceSuspendedMediaLocked(activeRoom)
        updateDesiredInterruptionStateLocked(
            activeRoom = activeRoom,
            mediaCall = current,
            operationId = request.operationId,
            phase = SystemCallInterruptionPhase.Suspended,
            degraded = failure != null,
        )
        if (failure == null) {
            MediaSystemCallInterruptionResult.Applied(current.generation)
        } else {
            logger.e(
                LOG_TAG,
                failure,
                "callId=${current.callId} generation=${current.generation} " +
                    "operationId=${request.operationId} phase=livekit.interruption event=suspend_degraded",
            )
            MediaSystemCallInterruptionResult.Degraded(
                generation = current.generation,
                reasonCode = "local_media_cutoff_failed",
            )
        }
    }

    override suspend fun resumeAfterSystemCall(
        request: MediaSystemCallResumeRequest,
    ): MediaSystemCallInterruptionResult = lifecycleMutex.withLock {
        val current = activeCall.get()
            ?: return@withLock MediaSystemCallInterruptionResult.Stale(
                generation = null,
                reasonCode = "no_active_media_call",
            )
        val activeRoom = room
        if (
            current.callId != request.callId ||
            current.generation != request.expectedGeneration ||
            current.interruptionOperationId != request.operationId ||
            activeRoom == null ||
            !isCurrent(activeRoom, current.generation)
        ) {
            return@withLock MediaSystemCallInterruptionResult.Stale(
                generation = current.generation,
                reasonCode = "interruption_identity_mismatch",
            )
        }

        updateDesiredInterruptionStateLocked(
            activeRoom = activeRoom,
            mediaCall = current,
            operationId = request.operationId,
            phase = SystemCallInterruptionPhase.Resuming,
            degraded = false,
        )
        if (activeRoom.state == Room.State.RECONNECTING) {
            return@withLock MediaSystemCallInterruptionResult.PausedReconnecting(current.generation)
        }
        if (activeRoom.state != Room.State.CONNECTED) {
            return@withLock MediaSystemCallInterruptionResult.TerminalFailure(
                generation = current.generation,
                reasonCode = "room_not_connected",
            )
        }

        current.systemSuspended = true
        val failure = runCatching {
            val microphoneChanged = activeRoom.localParticipant.setMicrophoneEnabled(
                request.enableMicrophone,
            )
            check(microphoneChanged) { "Microphone restore was rejected" }
            setRemoteAudioVolumeLocked(activeRoom, NORMAL_REMOTE_VOLUME)
            check(activeRoom.state == Room.State.CONNECTED) {
                "Room left connected state during media restore"
            }

            current.systemSuspended = false
            // Cover a track subscription racing the first sweep.
            setRemoteAudioVolumeLocked(activeRoom, NORMAL_REMOTE_VOLUME)
            check(activeRoom.state == Room.State.CONNECTED) {
                "Room left connected state during final media sweep"
            }
            if (request.enableMicrophone) {
                attachLocalAudioLevel(activeRoom)
            } else {
                detachLocalAudioLevel()
            }
        }.exceptionOrNull()

        if (failure != null) {
            current.systemSuspended = true
            bestEffortRollbackToSuspendedLocked(activeRoom, failure)
            logger.e(
                LOG_TAG,
                failure,
                "callId=${current.callId} generation=${current.generation} " +
                    "operationId=${request.operationId} phase=livekit.interruption event=resume_failed",
            )
            return@withLock MediaSystemCallInterruptionResult.Failed(
                generation = current.generation,
                reasonCode = "local_media_restore_failed",
            )
        }

        updateDesiredInterruptionStateLocked(
            activeRoom = activeRoom,
            mediaCall = current,
            operationId = request.operationId,
            phase = SystemCallInterruptionPhase.Active,
            degraded = false,
        )
        current.interruptionOperationId = null
        MediaSystemCallInterruptionResult.Applied(current.generation)
    }

    private suspend fun connect(command: MediaCallCommand.Connect) = lifecycleMutex.withLock {
        val connectStartedAt = System.nanoTime()
        logger.d(
            LOG_TAG,
            "callId=${command.callId} phase=livekit.connect event=begin wsUrl=${command.connection.wsUrl}",
        )
        val existing = activeCall.get()
        if (existing != null) {
            if (existing.callId == command.callId) return@withLock
            throw IllegalStateException(
                "A different LiveKit call is already active: ${existing.callId}",
            )
        }
        if (room != null) releaseRoomAsyncLocked()

        val generation = generationCounter.incrementAndGet()
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
                    // Publishing is enabled only after the room is connected
                    // and the termination signal has been checked below.
                    // Subscribing must stay enabled so the peer microphone is
                    // delivered even when local publication starts manually.
                    autoSubscribe = true,
                    audio = false,
                    video = false,
                ),
            )
            logger.d(
                LOG_TAG,
                "callId=${command.callId} phase=livekit.connect event=signaling_connected elapsedMs=${elapsedMillis(connectStartedAt)}",
            )
            logRoomSnapshot(command.callId, createdRoom, "after_signaling")
            command.terminationSignal.throwIfRequested()

            if (!isCurrent(createdRoom, generation)) return@withLock

            val microphoneEnabled = createdRoom.localParticipant.setMicrophoneEnabled(true)
            logger.d(
                LOG_TAG,
                "callId=${command.callId} phase=livekit.publish event=microphone_enable result=$microphoneEnabled",
            )
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
            logger.d(
                LOG_TAG,
                "callId=${command.callId} phase=livekit.connect event=media_ready elapsedMs=${elapsedMillis(connectStartedAt)} localIdentity=$localIdentity remoteCount=${createdRoom.remoteParticipants.size}",
            )
            logRoomSnapshot(command.callId, createdRoom, "media_ready")
        } catch (throwable: Throwable) {
            logger.e(
                LOG_TAG,
                throwable,
                "callId=${command.callId} phase=livekit.connect event=error elapsedMs=${elapsedMillis(connectStartedAt)}",
            )
            room?.let { logRoomSnapshot(command.callId, it, "connect_error") }
            val shouldPublishFailure = isActiveGeneration(command.callId, generation)
            if (shouldPublishFailure) {
                mediaCall.attributePublishJob?.cancel()
                activeCall.set(null)
                generationCounter.incrementAndGet()
                releaseRoomAsyncLocked()
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
        current.attributePublishJob?.cancel()
        activeCall.set(null)
        generationCounter.incrementAndGet()
        // A user hang-up is a local state transition, not a synchronous wait for
        // WebRTC/DTLS shutdown.  Detach all process-visible references now and
        // let the native room finish disconnecting in ApplicationScope.  This
        // keeps the next call and the UI independent from a slow peer/network.
        releaseRoomAsyncLocked()
    }

    private suspend fun setMuted(command: MediaCallCommand.SetMuted) = lifecycleMutex.withLock {
        val current = activeCall.get() ?: return@withLock
        if (current.callId != command.callId) return@withLock
        val activeRoom = room ?: return@withLock
        if (current.systemSuspended) {
            logger.d(
                LOG_TAG,
                "callId=${current.callId} generation=${current.generation} " +
                    "phase=livekit.mute event=ignored_system_suspended muted=${command.muted}",
            )
            return@withLock
        }
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

    private suspend fun enforceSuspendedMediaLocked(activeRoom: Room): Throwable? {
        var failure: Throwable? = null
        try {
            val changed = activeRoom.localParticipant.setMicrophoneEnabled(false)
            if (!changed) failure = IllegalStateException("Microphone cutoff was rejected")
        } catch (error: Throwable) {
            failure = error
        }
        try {
            setRemoteAudioVolumeLocked(activeRoom, MUTED_REMOTE_VOLUME)
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        detachLocalAudioLevel()
        return failure
    }

    private suspend fun bestEffortRollbackToSuspendedLocked(
        activeRoom: Room,
        restoreFailure: Throwable,
    ) {
        runCatching { activeRoom.localParticipant.setMicrophoneEnabled(false) }
            .onFailure(restoreFailure::addSuppressed)
        runCatching { setRemoteAudioVolumeLocked(activeRoom, MUTED_REMOTE_VOLUME) }
            .onFailure(restoreFailure::addSuppressed)
        detachLocalAudioLevel()
    }

    private fun setRemoteAudioVolumeLocked(activeRoom: Room, volume: Double) {
        activeRoom.remoteParticipants.values
            .asSequence()
            .flatMap { participant -> participant.audioTrackPublications.asSequence() }
            .mapNotNull { (_, track) -> track as? RemoteAudioTrack }
            .forEach { track -> track.setVolume(volume) }
    }

    private fun updateDesiredInterruptionStateLocked(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
        operationId: String,
        phase: SystemCallInterruptionPhase,
        degraded: Boolean,
    ) {
        val existing = mediaCall.desiredInterruption
        if (
            existing?.operationId == operationId &&
            existing.phase == phase &&
            existing.degraded == degraded
        ) {
            ensureAttributePublisherLocked(activeRoom, mediaCall, force = false)
            return
        }
        val desired = DesiredInterruptionState(
            operationId = operationId,
            phase = phase,
            degraded = degraded,
            encoded = SystemCallInterruptionWireCodec.encode(
                SystemCallInterruptionWireState(
                    v = SystemCallInterruptionWireCodec.SCHEMA_VERSION,
                    callId = mediaCall.callId,
                    senderGeneration = mediaCall.generation,
                    senderSessionId = mediaCall.senderSessionId,
                    sequence = ++mediaCall.interruptionSequence,
                    operationId = operationId,
                    phase = phase.toWireValue(),
                    degraded = degraded,
                ),
            ),
        )
        mediaCall.desiredInterruption = desired
        ensureAttributePublisherLocked(activeRoom, mediaCall, force = true)
    }

    private fun ensureAttributePublisherLocked(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
        force: Boolean,
    ) {
        val desired = mediaCall.desiredInterruption ?: return
        if (!force && mediaCall.attributePublishJob?.isActive == true) return
        mediaCall.attributePublishJob?.cancel()
        mediaCall.attributePublishJob = scope.launch {
            var backoffMillis = ATTRIBUTE_INITIAL_RETRY_MILLIS
            var publishEvenIfConfirmed = force
            while (currentCoroutineContext().isActive) {
                val status = lifecycleMutex.withLock {
                    if (!isCurrent(activeRoom, mediaCall.generation)) {
                        return@withLock AttributePublishStatus.Stale
                    }
                    val latest = mediaCall.desiredInterruption
                    if (latest?.encoded != desired.encoded) {
                        return@withLock AttributePublishStatus.Stale
                    }
                    if (
                        !publishEvenIfConfirmed &&
                        activeRoom.localParticipant.attributes[
                            SystemCallInterruptionWireCodec.ATTRIBUTE_KEY
                        ] == desired.encoded
                    ) {
                        return@withLock AttributePublishStatus.Confirmed
                    }
                    runCatching {
                        activeRoom.localParticipant.updateAttributes(
                            mapOf(SystemCallInterruptionWireCodec.ATTRIBUTE_KEY to desired.encoded),
                        )
                    }.fold(
                        onSuccess = {
                            publishEvenIfConfirmed = false
                            AttributePublishStatus.Pending
                        },
                        onFailure = { error ->
                            logger.e(
                                LOG_TAG,
                                error,
                                "callId=${mediaCall.callId} generation=${mediaCall.generation} " +
                                    "operationId=${desired.operationId} " +
                                    "phase=livekit.interruption.attribute event=publish_error",
                            )
                            AttributePublishStatus.Pending
                        },
                    )
                }
                if (status != AttributePublishStatus.Pending) return@launch
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(ATTRIBUTE_MAX_RETRY_MILLIS)
            }
        }
    }

    private fun observeRoomEvents(activeRoom: Room, mediaCall: ActiveMediaCall) {
        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            activeRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        logRoomEvent(activeRoom, mediaCall, event = "connected")
                        attachRemoteAudioLevel(activeRoom)
                        publishParticipantChanged(activeRoom, mediaCall)
                        publishRemoteInterruptionSnapshot(activeRoom, mediaCall)
                    }

                    // LiveKit owns the retry window. Reconnecting is a transient transport
                    // state and must not be promoted to a business-level hang-up.
                    is RoomEvent.Reconnecting -> {
                        logRoomEvent(activeRoom, mediaCall, event = "reconnecting")
                        publishReconnecting(activeRoom, mediaCall)
                    }

                    is RoomEvent.Reconnected -> {
                        logRoomEvent(activeRoom, mediaCall, event = "reconnected")
                        publishReconnected(activeRoom, mediaCall)
                    }

                    is RoomEvent.Disconnected -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "disconnected",
                            details = "reason=${safeValue { event.reason }} error=${safeValue { event.error?.message }}",
                        )
                        terminateCurrentCall(
                            activeRoom = activeRoom,
                            mediaCall = mediaCall,
                            failure = event.error,
                        )
                    }

                    is RoomEvent.ParticipantConnected -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "participant_connected",
                            details = "participant=${safeValue { event.participant.identity?.value }}",
                        )
                        attachRemoteAudioLevel(activeRoom)
                        publishParticipantChanged(activeRoom, mediaCall)
                        publishRemoteInterruptionSnapshot(activeRoom, mediaCall)
                    }

                    is RoomEvent.ParticipantDisconnected -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "participant_disconnected",
                            details = "participant=${safeValue { event.participant.identity?.value }}",
                        )
                        attachRemoteAudioLevel(activeRoom)
                        publishParticipantChanged(activeRoom, mediaCall)
                        publishRemoteInterruptionSnapshot(activeRoom, mediaCall)
                    }

                    is RoomEvent.TrackSubscribed -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_subscribed",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.publication.sid }} kind=${safeValue { event.track.kind }}",
                        )
                        (event.track as? RemoteAudioTrack)?.let { remoteTrack ->
                            applySystemSuspensionToSubscribedTrack(
                                activeRoom = activeRoom,
                                mediaCall = mediaCall,
                                remoteTrack = remoteTrack,
                            )
                            attachRemoteAudioLevel(remoteTrack)
                        }
                    }

                    is RoomEvent.TrackPublicationFailed -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_publication_failed",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "kind=${safeValue { event.track.kind }}",
                        )
                    }

                    is RoomEvent.TrackSubscriptionFailed -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_subscription_failed",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.sid }} error=${safeValue { event.exception.message }}",
                        )
                    }

                    is RoomEvent.TrackStreamStateChanged -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_stream_state_changed",
                            details = "sid=${safeValue { event.trackPublication.sid }} " +
                                "source=${safeValue { event.trackPublication.source }} " +
                                "state=${safeValue { event.streamState }}",
                        )
                    }

                    is RoomEvent.ParticipantStateChanged -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "participant_state_changed",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "old=${safeValue { event.oldState }} new=${safeValue { event.newState }}",
                        )
                    }

                    is RoomEvent.TrackPublished -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_published",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.publication.sid }} " +
                                "source=${safeValue { event.publication.source }} " +
                                "kind=${safeValue { event.publication.kind }} " +
                                "muted=${safeValue { event.publication.muted }}",
                        )
                        attachRemoteAudioLevel(activeRoom)
                    }

                    is RoomEvent.TrackUnpublished -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_unpublished",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.publication.sid }} " +
                                "source=${safeValue { event.publication.source }}",
                        )
                        attachRemoteAudioLevel(activeRoom)
                    }

                    is RoomEvent.TrackUnsubscribed -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_unsubscribed",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.publications.sid }} kind=${safeValue { event.track.kind }}",
                        )
                        if (event.track === remoteAudioLevelTrack) {
                            detachRemoteAudioLevel()
                            attachRemoteAudioLevel(activeRoom)
                        }
                    }

                    is RoomEvent.TrackMuted -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_muted",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.publication.sid }} " +
                                "source=${safeValue { event.publication.source }}",
                        )
                    }

                    is RoomEvent.TrackUnmuted -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_unmuted",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.publication.sid }} " +
                                "source=${safeValue { event.publication.source }}",
                        )
                        attachRemoteAudioLevel(activeRoom)
                    }

                    is RoomEvent.TrackSubscriptionPermissionChanged -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "track_subscription_permission_changed",
                            details = "participant=${safeValue { event.participant.identity?.value }} " +
                                "sid=${safeValue { event.trackPublication.sid }} " +
                                "allowed=${safeValue { event.subscriptionAllowed }}",
                        )
                    }

                    is RoomEvent.ConnectionQualityChanged -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "connection_quality_changed",
                            details = "participant=${safeValue { event.participant.identity?.value }} quality=${safeValue { event.quality }}",
                        )
                        if (event.participant.identity == activeRoom.localParticipant.identity) {
                            publishNetworkQuality(activeRoom, mediaCall, event.quality)
                        }
                    }

                    is RoomEvent.ParticipantAttributesChanged -> {
                        if (event.participant.identity != activeRoom.localParticipant.identity) {
                            publishRemoteInterruptionSnapshot(activeRoom, mediaCall)
                        }
                    }

                    is RoomEvent.FailedToConnect -> {
                        logRoomEvent(
                            activeRoom,
                            mediaCall,
                            event = "failed_to_connect",
                            details = "error=${safeValue { event.error.message }}",
                        )
                        terminateCurrentCall(
                            activeRoom = activeRoom,
                            mediaCall = mediaCall,
                            failure = event.error,
                        )
                    }

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
            if (mediaCall.systemSuspended) {
                val failure = enforceSuspendedMediaLocked(activeRoom)
                val desired = mediaCall.desiredInterruption
                val operationId = mediaCall.interruptionOperationId
                if (desired != null && operationId != null) {
                    updateDesiredInterruptionStateLocked(
                        activeRoom = activeRoom,
                        mediaCall = mediaCall,
                        operationId = operationId,
                        phase = desired.phase,
                        degraded = failure != null,
                    )
                }
            } else {
                reattachLocalAudioLevel(activeRoom)
                setRemoteAudioVolumeLocked(activeRoom, NORMAL_REMOTE_VOLUME)
            }
            ensureAttributePublisherLocked(activeRoom, mediaCall, force = true)
            publishRemoteInterruptionSnapshotLocked(activeRoom, mediaCall)
            reattachRemoteAudioLevel(activeRoom)
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

    private suspend fun applySystemSuspensionToSubscribedTrack(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
        remoteTrack: RemoteAudioTrack,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation) || !mediaCall.systemSuspended) {
                return@withLock
            }
            runCatching { remoteTrack.setVolume(MUTED_REMOTE_VOLUME) }
                .onFailure { error ->
                    logger.e(
                        LOG_TAG,
                        error,
                        "callId=${mediaCall.callId} generation=${mediaCall.generation} " +
                            "phase=livekit.interruption event=new_track_cutoff_error",
                    )
                }
        }
    }

    private suspend fun publishRemoteInterruptionSnapshot(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
    ) {
        lifecycleMutex.withLock {
            if (!isCurrent(activeRoom, mediaCall.generation)) return@withLock
            publishRemoteInterruptionSnapshotLocked(activeRoom, mediaCall)
        }
    }

    private suspend fun publishRemoteInterruptionSnapshotLocked(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
    ) {
        val participant = activeRoom.remoteParticipants.values.firstOrNull()
        if (participant == null) {
            val previous = mediaCall.remoteInterruptionCursor ?: return
            mediaCall.remoteInterruptionCursor = null
            eventBus.emit(
                MediaCallEvent.RemoteSystemCallInterruptionChanged(
                    callId = mediaCall.callId,
                    generation = mediaCall.generation,
                    operationId = previous.operationId,
                    phase = SystemCallInterruptionPhase.Active,
                    degraded = false,
                ),
            )
            return
        }

        val participantSid = participant.sid.value
        val encoded = participant.attributes[SystemCallInterruptionWireCodec.ATTRIBUTE_KEY]
        if (encoded == null) {
            val previous = mediaCall.remoteInterruptionCursor
            if (previous != null) {
                mediaCall.remoteInterruptionCursor = null
                eventBus.emit(
                    MediaCallEvent.RemoteSystemCallInterruptionChanged(
                        callId = mediaCall.callId,
                        generation = mediaCall.generation,
                        operationId = previous.operationId,
                        phase = SystemCallInterruptionPhase.Active,
                        degraded = false,
                    ),
                )
            }
            return
        }

        val state = SystemCallInterruptionWireCodec.decode(encoded) ?: run {
            val previous = mediaCall.remoteInterruptionCursor
            if (previous != null && previous.participantSid != participantSid) {
                mediaCall.remoteInterruptionCursor = null
                eventBus.emit(
                    MediaCallEvent.RemoteSystemCallInterruptionChanged(
                        callId = mediaCall.callId,
                        generation = mediaCall.generation,
                        operationId = previous.operationId,
                        phase = SystemCallInterruptionPhase.Active,
                        degraded = false,
                    ),
                )
            }
            logger.d(
                LOG_TAG,
                "callId=${mediaCall.callId} generation=${mediaCall.generation} " +
                    "phase=livekit.interruption.remote event=invalid_attribute",
            )
            return
        }
        if (state.callId != mediaCall.callId) {
            logger.d(
                LOG_TAG,
                "callId=${mediaCall.callId} generation=${mediaCall.generation} " +
                    "phase=livekit.interruption.remote event=call_mismatch",
            )
            return
        }
        val phase = state.toPhase() ?: return
        val previous = mediaCall.remoteInterruptionCursor
        if (
            previous?.participantSid == participantSid &&
            previous.senderSessionId == state.senderSessionId &&
            state.sequence <= previous.sequence
        ) {
            return
        }
        mediaCall.remoteInterruptionCursor = RemoteInterruptionCursor(
            participantSid = participantSid,
            senderSessionId = state.senderSessionId,
            sequence = state.sequence,
            operationId = state.operationId,
        )
        eventBus.emit(
            MediaCallEvent.RemoteSystemCallInterruptionChanged(
                callId = mediaCall.callId,
                generation = mediaCall.generation,
                operationId = state.operationId,
                phase = phase,
                degraded = state.degraded,
            ),
        )
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
            mediaCall.attributePublishJob?.cancel()
            activeCall.set(null)
            generationCounter.incrementAndGet()
            releaseRoomAsyncLocked()
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

    private fun reattachLocalAudioLevel(activeRoom: Room) {
        // LiveKit may recreate the native WebRTC track while preserving the Kotlin
        // LocalAudioTrack wrapper during reconnect. Rebind even when object identity
        // is unchanged so the PCM meter starts receiving frames again.
        detachLocalAudioLevel()
        attachLocalAudioLevel(activeRoom)
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

    private fun logRoomEvent(
        activeRoom: Room,
        mediaCall: ActiveMediaCall,
        event: String,
        details: String? = null,
    ) {
        runCatching {
            logger.d(
                LOG_TAG,
                "callId=${mediaCall.callId} phase=livekit.event event=$event " +
                    "roomState=${activeRoom.state} localIdentity=${activeRoom.localParticipant.identity?.value} " +
                    "remoteCount=${activeRoom.remoteParticipants.size} " +
                    "${roomAudioSnapshot(activeRoom)}" +
                    (details?.let { " $it" } ?: ""),
            )
        }
    }

    private fun logRoomSnapshot(callId: String, activeRoom: Room, event: String) {
        runCatching {
            logger.d(
                LOG_TAG,
                "callId=$callId phase=livekit.snapshot event=$event " +
                    "roomState=${activeRoom.state} localIdentity=${activeRoom.localParticipant.identity?.value} " +
                    "remoteCount=${activeRoom.remoteParticipants.size} ${roomAudioSnapshot(activeRoom)}",
            )
        }
    }

    private fun roomAudioSnapshot(activeRoom: Room): String {
        val localPublication = activeRoom.localParticipant.audioTrackPublications
            .firstOrNull { (publication, _) -> publication.source == Track.Source.MICROPHONE }
            ?.first
        val local = localPublication?.let {
            "localMic=sid:${it.sid},muted:${it.muted},subscribed:${it.subscribed},track:${it.track != null}"
        } ?: "localMic=none"
        val remote = activeRoom.remoteParticipants.values.joinToString(",") { participant ->
            val publication = participant.audioTrackPublications
                .firstOrNull { (candidate, _) -> candidate.source == Track.Source.MICROPHONE }
            val identity = participant.identity?.value ?: "?"
            val description = publication?.first?.let {
                "sid:${it.sid},muted:${it.muted},subscribed:${it.subscribed},track:${it.track != null}"
            } ?: "none"
            "$identity[$description]"
        }.ifBlank { "none" }
        return "$local remoteMics=$remote"
    }

    private fun safeValue(block: () -> Any?): String = runCatching {
        block()?.toString() ?: "null"
    }.getOrDefault("unavailable")

    private fun attachRemoteAudioLevel(remoteTrack: RemoteAudioTrack) {
        if (remoteAudioLevelTrack === remoteTrack) return
        detachRemoteAudioLevel()
        runCatching { remoteTrack.addSink(audioLevelProvider.remoteAudioSink) }
            .onSuccess { remoteAudioLevelTrack = remoteTrack }
            .onFailure { audioLevelProvider.resetRemote() }
    }

    private fun reattachRemoteAudioLevel(activeRoom: Room) {
        // Keep both overlay meters aligned with the media generation restored by
        // LiveKit, including reconnects that retain the same track wrapper.
        detachRemoteAudioLevel()
        attachRemoteAudioLevel(activeRoom)
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

    /** Detaches the room immediately and performs native shutdown off the call path. */
    private fun releaseRoomAsyncLocked() {
        roomEventsJob?.cancel()
        roomEventsJob = null
        detachLocalAudioLevel()
        detachRemoteAudioLevel()
        val detachedRoom = room
        room = null
        roomStateProvider.update(null)
        if (detachedRoom == null) return

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.IO) {
                val disconnectStartedAt = System.nanoTime()
                logger.d(LOG_TAG, "phase=livekit.disconnect event=begin async=true")
                runCatching { detachedRoom.disconnect() }
                    .onSuccess {
                        logger.d(
                            LOG_TAG,
                            "phase=livekit.disconnect event=end async=true elapsedMs=${elapsedMillis(disconnectStartedAt)}",
                        )
                    }
                    .onFailure { throwable ->
                        logger.e(
                            LOG_TAG,
                            throwable,
                            "phase=livekit.disconnect event=error async=true elapsedMs=${elapsedMillis(disconnectStartedAt)}",
                        )
                    }
                val releaseStartedAt = System.nanoTime()
                logger.d(LOG_TAG, "phase=livekit.release event=begin async=true")
                runCatching { detachedRoom.release() }
                    .onSuccess {
                        logger.d(
                            LOG_TAG,
                            "phase=livekit.release event=end async=true elapsedMs=${elapsedMillis(releaseStartedAt)}",
                        )
                    }
                    .onFailure { throwable ->
                        logger.e(
                            LOG_TAG,
                            throwable,
                            "phase=livekit.release event=error async=true elapsedMs=${elapsedMillis(releaseStartedAt)}",
                        )
                    }
            }
        }
    }

    private data class ActiveMediaCall(
        val callId: String,
        val generation: Long,
        val senderSessionId: String = UUID.randomUUID().toString(),
        var systemSuspended: Boolean = false,
        var interruptionOperationId: String? = null,
        var interruptionSequence: Long = 0L,
        var desiredInterruption: DesiredInterruptionState? = null,
        var attributePublishJob: Job? = null,
        var remoteInterruptionCursor: RemoteInterruptionCursor? = null,
    )

    private data class DesiredInterruptionState(
        val operationId: String,
        val phase: SystemCallInterruptionPhase,
        val degraded: Boolean,
        val encoded: String,
    )

    private data class RemoteInterruptionCursor(
        val participantSid: String,
        val senderSessionId: String,
        val sequence: Long,
        val operationId: String,
    )

    private enum class AttributePublishStatus {
        Pending,
        Confirmed,
        Stale,
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        const val LOG_TAG = "CallLiveKit"
        const val EVENT_BUFFER_SIZE = 64
        const val MUTED_REMOTE_VOLUME = 0.0
        const val NORMAL_REMOTE_VOLUME = 1.0
        const val ATTRIBUTE_INITIAL_RETRY_MILLIS = 1_000L
        const val ATTRIBUTE_MAX_RETRY_MILLIS = 5_000L
    }
}

private fun ConnectionQuality.toScore(): Int = when (this) {
    ConnectionQuality.EXCELLENT -> 5
    ConnectionQuality.GOOD -> 4
    ConnectionQuality.POOR -> 2
    ConnectionQuality.LOST -> 1
    ConnectionQuality.UNKNOWN -> 3
}
