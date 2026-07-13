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

    private fun session() = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "pending"),
        roomName = "room-1",
        connectionState = CallConnectionState.Connecting,
        localAudioState = LocalAudioState.Enabling,
    )
}

