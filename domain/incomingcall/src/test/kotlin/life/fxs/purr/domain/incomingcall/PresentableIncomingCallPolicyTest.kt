package life.fxs.purr.domain.incomingcall

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import org.junit.Test

class PresentableIncomingCallPolicyTest {
    @Test
    fun `matching local session suppresses the same incoming call permanently`() {
        val candidate = incomingCall("call-1")

        assertThat(
            PresentableIncomingCallPolicy.resolve(candidate, session("call-1", CallConnectionState.Connected)),
        ).isNull()
        assertThat(
            PresentableIncomingCallPolicy.resolve(candidate, session("call-1", CallConnectionState.Disconnected)),
        ).isNull()
    }

    @Test
    fun `active local call blocks a different incoming presentation`() {
        val result = PresentableIncomingCallPolicy.resolve(
            incomingCall("call-2"),
            session("call-1", CallConnectionState.Connected),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `different terminal session allows a genuinely new call`() {
        val candidate = incomingCall("call-2")

        val result = PresentableIncomingCallPolicy.resolve(
            candidate,
            session("call-1", CallConnectionState.Disconnected),
        )

        assertThat(result).isEqualTo(candidate)
    }

    private fun incomingCall(callId: String) = IncomingCall(callId, "pair-1", "user-b", 1L)

    private fun session(callId: String, state: CallConnectionState) = CallSession(
        callId = callId,
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "user-a", remote = "user-b"),
        roomName = "room-1",
        connectionState = state,
    )
}
