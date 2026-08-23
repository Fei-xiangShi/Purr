package life.fxs.purr.domain.call.model

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.CallDirection
import org.junit.Test

class CallLifecycleStateTest {
    @Test
    fun `terminal session is callable while disconnect synchronization completes`() {
        val session = session(CallConnectionState.Disconnected)

        val pending = CallLifecycleState(session, disconnectingCallId = session.callId)
        val complete = CallLifecycleState(session)

        assertThat(pending.resumableSession).isNull()
        assertThat(pending.isTerminationInProgress).isFalse()
        assertThat(pending.isNewCallBlocked).isFalse()
        assertThat(complete.isTerminationInProgress).isFalse()
        assertThat(complete.isNewCallBlocked).isFalse()
    }

    @Test
    fun `resumable session preserves exact call identity and direction`() {
        val session = session(CallConnectionState.Reconnecting)

        val resumable = CallLifecycleState(session = session).resumableSession

        assertThat(resumable?.callId).isEqualTo("call-1")
        assertThat(resumable?.pairId).isEqualTo("pair-1")
        assertThat(resumable?.direction).isEqualTo(CallDirection.Incoming)
    }

    @Test
    fun `disconnect barrier suppresses a stale resumable snapshot`() {
        val lifecycle = CallLifecycleState(
            session = session(CallConnectionState.Reconnecting),
            disconnectingCallId = "call-1",
        )

        assertThat(lifecycle.resumableSession).isNull()
        assertThat(lifecycle.isTerminationInProgress).isTrue()
        assertThat(lifecycle.isNewCallBlocked).isTrue()
    }

    private fun session(connectionState: CallConnectionState) = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self", remote = "partner"),
        roomName = "room-1",
        direction = CallDirection.Incoming,
        connectionState = connectionState,
    )
}
