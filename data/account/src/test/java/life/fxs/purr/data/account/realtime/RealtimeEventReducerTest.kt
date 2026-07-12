package life.fxs.purr.data.account.realtime

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.network.model.ActiveCallDto
import life.fxs.purr.core.network.model.RealtimeEventDto
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import org.junit.Test

class RealtimeEventReducerTest {
    @Test
    fun `snapshot updates presence and restores incoming call`() {
        val result = RealtimeState().applyRealtimeEvent(
            event = incomingEvent(type = "snapshot"),
            currentUserId = "user-a",
        )

        assertThat(result.partnerOnline).isTrue()
        assertThat(result.incomingCall).isEqualTo(incomingCall())
    }

    @Test
    fun `call created by current user is not treated as incoming`() {
        val result = RealtimeState(incomingCall = incomingCall()).applyRealtimeEvent(
            event = incomingEvent(type = "call_started", callerUserId = "user-a"),
            currentUserId = "user-a",
        )

        assertThat(result.incomingCall).isNull()
    }

    @Test
    fun `presence event preserves incoming call`() {
        val result = RealtimeState(incomingCall = incomingCall()).applyRealtimeEvent(
            event = RealtimeEventDto(type = "presence_changed", partnerOnline = false),
            currentUserId = "user-a",
        )

        assertThat(result.partnerOnline).isFalse()
        assertThat(result.incomingCall).isEqualTo(incomingCall())
    }

    @Test
    fun `call ended only clears matching incoming call`() {
        val state = RealtimeState(incomingCall = incomingCall())

        val unrelated = state.applyRealtimeEvent(
            event = RealtimeEventDto(type = "call_ended", callId = "call-2"),
            currentUserId = "user-a",
        )
        val matching = state.applyRealtimeEvent(
            event = RealtimeEventDto(type = "call_ended", callId = "call-1"),
            currentUserId = "user-a",
        )

        assertThat(unrelated.incomingCall).isEqualTo(incomingCall())
        assertThat(matching.incomingCall).isNull()
    }

    @Test
    fun `active call fallback only restores incoming calls`() {
        val incoming = activeCall(isIncoming = true).toIncomingCallOrNull()
        val outgoing = activeCall(isIncoming = false).toIncomingCallOrNull()

        assertThat(incoming).isEqualTo(incomingCall())
        assertThat(outgoing).isNull()
    }

    private fun incomingEvent(
        type: String,
        callerUserId: String = "user-b",
    ) = RealtimeEventDto(
        type = type,
        partnerOnline = true,
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = callerUserId,
        startedAtEpochMillis = 1L,
    )

    private fun activeCall(isIncoming: Boolean) = ActiveCallDto(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "user-b",
        isIncoming = isIncoming,
        startedAtEpochMillis = 1L,
    )

    private fun incomingCall() = IncomingCall(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "user-b",
        startedAtEpochMillis = 1L,
    )
}
