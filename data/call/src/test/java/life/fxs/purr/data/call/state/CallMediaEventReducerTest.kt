package life.fxs.purr.data.call.state

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.data.call.runtime.MediaCallEvent
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
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

    private fun session() = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "pending"),
        roomName = "room-1",
        connectionState = CallConnectionState.Connecting,
        localAudioState = LocalAudioState.Enabling,
    )
}
