package life.fxs.purr.feature.incomingcall

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import org.junit.Test

class PresentableIncomingCallPolicyTest {
    @Test
    fun `matching local session permanently consumes the same incoming call`() {
        val candidate = incomingCall("call-1")

        val whileConnected = PresentableIncomingCallPolicy.resolve(
            candidate,
            session("call-1", CallConnectionState.Connected),
        )
        val afterDisconnect = PresentableIncomingCallPolicy.resolve(
            candidate,
            session("call-1", CallConnectionState.Disconnected),
        )

        assertThat(whileConnected).isNull()
        assertThat(afterDisconnect).isNull()
    }

    @Test
    fun `an active local call blocks another incoming presentation`() {
        val result = PresentableIncomingCallPolicy.resolve(
            incomingCall("call-2"),
            session("call-1", CallConnectionState.Connected),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `a different terminal session does not block a genuinely new call`() {
        val candidate = incomingCall("call-2")

        val result = PresentableIncomingCallPolicy.resolve(
            candidate,
            session("call-1", CallConnectionState.Disconnected),
        )

        assertThat(result).isEqualTo(candidate)
    }

    private fun incomingCall(callId: String) = IncomingCall(
        callId = callId,
        pairId = "pair-1",
        callerUserId = "user-b",
        startedAtEpochMillis = 1L,
    )

    private fun session(callId: String, state: CallConnectionState) = CallSession(
        callId = callId,
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "user-a", remote = "user-b"),
        roomName = "room-1",
        connectionState = state,
    )
}
