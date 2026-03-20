package life.fxs.purr.data.call.livekit

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.media.livekit.MutableCallRoomStateProvider
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.NetworkQuality
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.RecordingState

@Singleton
class RealLiveKitCallDataSource @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val roomStateProvider: MutableCallRoomStateProvider,
) : LiveKitCallDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = MutableStateFlow<CallSession?>(null)

    override val sessionEvents: Flow<CallSession?> = events.asStateFlow()

    private var room: Room? = null
    private var latestSession: CallSession? = null
    private var roomEventsJob: Job? = null

    override suspend fun connect(session: CallSession) {
        releaseRoom()
        latestSession = session
        events.emit(session)

        val createdRoom = LiveKit.create(
            appContext = appContext,
            options = RoomOptions(),
        )
        room = createdRoom
        roomStateProvider.update(createdRoom)
        observeRoomEvents(createdRoom)

        try {
            createdRoom.connect(
                url = session.wsUrl,
                token = session.token,
                options = ConnectOptions(
                    audio = false,
                    video = false,
                ),
            )

            val microphoneEnabled = createdRoom.localParticipant.setMicrophoneEnabled(true)
            check(microphoneEnabled) { "Unable to publish microphone track" }

            val localIdentity = checkNotNull(createdRoom.localParticipant.identity) {
                "LiveKit local participant identity unavailable after connect"
            }.value

            emitSession(
                requireLatestSession().copy(
                    participantIdentity = requireLatestSession().participantIdentity.withResolvedIdentity(
                        local = localIdentity,
                        remote = createdRoom.remoteParticipants.keys.firstOrNull()?.value,
                    ),
                    connectionState = CallConnectionState.Connected,
                    localAudioState = LocalAudioState.Enabled,
                    recordingState = if (createdRoom.isRecording) RecordingState.Recording else requireLatestSession().recordingState,
                    uiSnapshot = requireLatestSession().uiSnapshot.copy(
                        remoteParticipantConnected = createdRoom.remoteParticipants.isNotEmpty(),
                    ),
                ),
            )
        } catch (throwable: Throwable) {
            releaseRoom()
            throw throwable
        }
    }

    override suspend fun disconnect() {
        releaseRoom()
        latestSession = null
        events.emit(null)
    }

    override suspend fun setMuted(muted: Boolean) {
        val activeRoom = room ?: error("LiveKit room is not connected")
        val changed = activeRoom.localParticipant.setMicrophoneEnabled(!muted)
        check(changed) { "Unable to update microphone state" }
        emitSession(
            requireLatestSession().copy(
                localAudioState = if (muted) LocalAudioState.Muted else LocalAudioState.Enabled,
            ),
        )
    }

    override fun updateSession(session: CallSession?) {
        latestSession = session
    }

    private fun observeRoomEvents(room: Room) {
        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            room.events.collect { event ->
                val current = latestSession ?: return@collect
                when (event) {
                    is RoomEvent.Connected,
                    is RoomEvent.Reconnected,
                    -> emitSession(
                        current.copy(
                            participantIdentity = current.participantIdentity.withResolvedIdentity(
                                local = room.localParticipant.identity?.value ?: current.participantIdentity.local,
                                remote = room.remoteParticipants.keys.firstOrNull()?.value,
                            ),
                            connectionState = CallConnectionState.Connected,
                            localAudioState = current.localAudioState.recoverAfterReconnect(),
                            recordingState = if (room.isRecording) RecordingState.Recording else current.recordingState,
                            uiSnapshot = current.uiSnapshot.copy(
                                remoteParticipantConnected = room.remoteParticipants.isNotEmpty(),
                            ),
                        ),
                    )

                    is RoomEvent.Reconnecting -> emitSession(
                        current.copy(connectionState = CallConnectionState.Reconnecting),
                    )

                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    -> emitSession(
                        current.copy(
                            participantIdentity = current.participantIdentity.copy(
                                remote = room.remoteParticipants.keys.firstOrNull()?.value,
                            ),
                            uiSnapshot = current.uiSnapshot.copy(
                                remoteParticipantConnected = room.remoteParticipants.isNotEmpty(),
                            ),
                        ),
                    )

                    is RoomEvent.ConnectionQualityChanged -> {
                        if (event.participant.identity == room.localParticipant.identity) {
                            emitSession(
                                current.copy(
                                    uiSnapshot = current.uiSnapshot.copy(
                                        networkQuality = NetworkQuality(
                                            uplinkScore = event.quality.toScore(),
                                            downlinkScore = event.quality.toScore(),
                                            lastUpdatedEpochMillis = System.currentTimeMillis(),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }

                    is RoomEvent.RecordingStatusChanged -> {
                        val nextRecordingState = when {
                            room.isRecording -> RecordingState.Recording
                            current.recordingState is RecordingState.Starting || current.recordingState is RecordingState.Stopping -> current.recordingState
                            else -> RecordingState.NotRecording
                        }
                        emitSession(
                            current.copy(recordingState = nextRecordingState),
                        )
                    }

                    is RoomEvent.Disconnected -> emitSession(
                        current.copy(
                            connectionState = CallConnectionState.Disconnected,
                            localAudioState = LocalAudioState.Disabled,
                            uiSnapshot = current.uiSnapshot.copy(remoteParticipantConnected = false),
                        ),
                    )

                    is RoomEvent.FailedToConnect -> emitSession(
                        current.copy(
                            connectionState = CallConnectionState.Failed(event.error.message),
                            localAudioState = LocalAudioState.Error(event.error.message),
                        ),
                    )

                    else -> Unit
                }
            }
        }
    }

    private suspend fun emitSession(session: CallSession) {
        latestSession = session
        events.emit(session)
    }

    private fun requireLatestSession(): CallSession = checkNotNull(latestSession)

    private fun releaseRoom() {
        roomEventsJob?.cancel()
        roomEventsJob = null
        room?.disconnect()
        room?.release()
        room = null
        roomStateProvider.update(null)
    }
}

private fun ParticipantIdentity.withResolvedIdentity(
    local: String,
    remote: String?,
): ParticipantIdentity {
    return copy(
        local = local,
        remote = remote ?: this.remote,
    )
}

private fun LocalAudioState.recoverAfterReconnect(): LocalAudioState = when (this) {
    LocalAudioState.Disabled,
    LocalAudioState.Enabling,
    is LocalAudioState.Error,
    -> LocalAudioState.Enabled

    LocalAudioState.Enabled,
    LocalAudioState.Muted,
    -> this
}

private fun ConnectionQuality.toScore(): Int = when (this) {
    ConnectionQuality.EXCELLENT -> 5
    ConnectionQuality.GOOD -> 4
    ConnectionQuality.POOR -> 2
    ConnectionQuality.LOST -> 1
    ConnectionQuality.UNKNOWN -> 3
}
