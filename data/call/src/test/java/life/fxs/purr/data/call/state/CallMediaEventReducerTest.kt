package life.fxs.purr.data.call.state

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.RemoteCallInterruption
import life.fxs.purr.core.model.SystemCallInterruptionPhase
import org.junit.Test

class CallMediaEventReducerTest {
    private val reducer = CallMediaEventReducer()

    @Test
    fun `connected media fact maps to domain state without provider types`() {
        val reduced = reducer.reduce(
            session = session(),
            event = MediaCallEvent.Connected(
                callId = "call-1",
                generation = 7L,
                localIdentity = "resolved-self",
                remoteIdentity = "remote",
                remoteParticipantConnected = true,
            ),
        )

        assertThat(reduced?.connectionState).isEqualTo(CallConnectionState.Connected)
        assertThat(reduced?.localAudioState).isEqualTo(LocalAudioState.Enabled)
        assertThat(reduced?.participantIdentity?.local).isEqualTo("resolved-self")
        assertThat(reduced?.participantIdentity?.remote).isEqualTo("remote")
        assertThat(reduced?.uiSnapshot?.remoteParticipantConnected).isTrue()
    }

    @Test
    fun `event for another call is ignored`() {
        val reduced = reducer.reduce(
            session = session(),
            event = MediaCallEvent.Disconnected(
                callId = "old-call",
                generation = 1L,
            ),
        )

        assertThat(reduced).isNull()
    }

    @Test
    fun `provider failure maps to one terminal domain state`() {
        val reduced = reducer.reduce(
            session = session(),
            event = MediaCallEvent.Failed(
                callId = "call-1",
                generation = 9L,
                reason = "transport failed",
            ),
        )

        assertThat(reduced?.connectionState)
            .isEqualTo(CallConnectionState.Failed("transport failed"))
        assertThat(reduced?.localAudioState)
            .isEqualTo(LocalAudioState.Error("transport failed"))
        assertThat(reduced?.uiSnapshot?.remoteParticipantConnected).isFalse()
    }

    @Test
    fun `reconnect lifecycle is non terminal and preserves microphone state`() {
        val reconnecting = reducer.reduce(
            session = session().copy(
                connectionState = CallConnectionState.Connected,
                localAudioState = LocalAudioState.Muted,
            ),
            event = MediaCallEvent.Reconnecting(
                callId = "call-1",
                generation = 7L,
            ),
        )

        assertThat(reconnecting?.connectionState).isEqualTo(CallConnectionState.Reconnecting)
        assertThat(reconnecting?.localAudioState).isEqualTo(LocalAudioState.Muted)

        val reconnected = reducer.reduce(
            session = requireNotNull(reconnecting),
            event = MediaCallEvent.Reconnected(
                callId = "call-1",
                generation = 7L,
                remoteIdentity = "remote-restored",
                remoteParticipantConnected = true,
            ),
        )

        assertThat(reconnected?.connectionState).isEqualTo(CallConnectionState.Connected)
        assertThat(reconnected?.localAudioState).isEqualTo(LocalAudioState.Muted)
        assertThat(reconnected?.participantIdentity?.remote).isEqualTo("remote-restored")
        assertThat(reconnected?.uiSnapshot?.remoteParticipantConnected).isTrue()
    }

    @Test
    fun `remote interruption facts are replayed independently from connection state`() {
        val connected = session().copy(connectionState = CallConnectionState.Connected)
        val suspended = reducer.reduce(
            connected,
            MediaCallEvent.RemoteSystemCallInterruptionChanged(
                callId = "call-1",
                generation = 7L,
                operationId = "operation-1",
                phase = SystemCallInterruptionPhase.Suspended,
                degraded = true,
            ),
        )
        val resuming = reducer.reduce(
            requireNotNull(suspended),
            MediaCallEvent.RemoteSystemCallInterruptionChanged(
                callId = "call-1",
                generation = 7L,
                operationId = "operation-1",
                phase = SystemCallInterruptionPhase.Resuming,
                degraded = false,
            ),
        )
        val active = reducer.reduce(
            requireNotNull(resuming),
            MediaCallEvent.RemoteSystemCallInterruptionChanged(
                callId = "call-1",
                generation = 7L,
                operationId = "operation-1",
                phase = SystemCallInterruptionPhase.Active,
                degraded = false,
            ),
        )

        assertThat(suspended?.interruptionState?.remote)
            .isEqualTo(RemoteCallInterruption.Suspended("operation-1", degraded = true))
        assertThat(resuming?.interruptionState?.remote)
            .isEqualTo(RemoteCallInterruption.Resuming("operation-1"))
        assertThat(active?.interruptionState?.remote).isEqualTo(RemoteCallInterruption.None)
        assertThat(active?.connectionState).isEqualTo(CallConnectionState.Connected)
    }

    private fun session() = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "pending"),
        roomName = "room-1",
        connectionState = CallConnectionState.Connecting,
        localAudioState = LocalAudioState.Enabling,
    )
}
