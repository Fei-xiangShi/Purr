package life.fxs.purr.data.call.state

import javax.inject.Inject
import life.fxs.purr.core.model.SystemCallInterruptionPhase
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.data.call.runtime.NetworkQualityDirection
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.NetworkQuality
import life.fxs.purr.domain.call.model.RemoteCallInterruption

/**
 * Translates provider-neutral media facts into the domain call state.
 *
 * Keeping this reducer outside the provider adapter means a LiveKit callback can
 * never mutate a domain session directly, and another media provider can reuse the
 * same lifecycle rules without importing LiveKit or Android classes.
 */
class CallMediaEventReducer @Inject constructor() {
    fun reduce(session: CallSession, event: MediaCallEvent): CallSession? {
        if (session.callId != event.callId) return null

        return when (event) {
            is MediaCallEvent.Connected -> session.copy(
                participantIdentity = session.participantIdentity.copy(
                    local = event.localIdentity,
                    remote = event.remoteIdentity,
                ),
                connectionState = CallConnectionState.Connected,
                localAudioState = LocalAudioState.Enabled,
                uiSnapshot = session.uiSnapshot.copy(
                    remoteParticipantConnected = event.remoteParticipantConnected,
                ),
            )

            is MediaCallEvent.Reconnecting -> session.copy(
                connectionState = CallConnectionState.Reconnecting,
            )

            is MediaCallEvent.Reconnected -> session.copy(
                participantIdentity = session.participantIdentity.copy(
                    remote = event.remoteIdentity,
                ),
                connectionState = CallConnectionState.Connected,
                uiSnapshot = session.uiSnapshot.copy(
                    remoteParticipantConnected = event.remoteParticipantConnected,
                ),
            )

            is MediaCallEvent.ParticipantChanged -> session.copy(
                interruptionState = session.interruptionState.copy(
                    remote = session.interruptionState.remote.takeIf { event.remoteParticipantConnected }
                        ?: RemoteCallInterruption.None,
                ),
                participantIdentity = session.participantIdentity.copy(
                    remote = event.remoteIdentity,
                ),
                uiSnapshot = session.uiSnapshot.copy(
                    remoteParticipantConnected = event.remoteParticipantConnected,
                ),
            )

            is MediaCallEvent.AudioStateChanged -> session.copy(
                localAudioState = if (event.muted) {
                    LocalAudioState.Muted
                } else {
                    LocalAudioState.Enabled
                },
            )

            is MediaCallEvent.NetworkQualityChanged -> session.copy(
                uiSnapshot = session.uiSnapshot.copy(
                    networkQuality = session.uiSnapshot.networkQuality.let { current ->
                        when (event.direction) {
                            NetworkQualityDirection.Uplink -> current.copy(uplinkScore = event.score)
                            NetworkQualityDirection.Downlink -> current.copy(downlinkScore = event.score)
                        }.copy(lastUpdatedEpochMillis = event.sampledAtEpochMillis)
                    },
                ),
            )

            is MediaCallEvent.RemoteSystemCallInterruptionChanged -> session.copy(
                interruptionState = session.interruptionState.copy(
                    remote = when (event.phase) {
                        SystemCallInterruptionPhase.Suspended ->
                            RemoteCallInterruption.Suspended(
                                operationId = event.operationId,
                                degraded = event.degraded,
                            )
                        SystemCallInterruptionPhase.Resuming ->
                            RemoteCallInterruption.Resuming(event.operationId)
                        SystemCallInterruptionPhase.Active ->
                            RemoteCallInterruption.None
                    },
                ),
            )

            // Session termination owns teardown, server end and the terminal state, so the
            // repository routes these facts to it instead of reducing them here.
            is MediaCallEvent.Disconnected,
            is MediaCallEvent.Failed,
            is MediaCallEvent.ConnectionLost,
            -> null
        }
    }
}
