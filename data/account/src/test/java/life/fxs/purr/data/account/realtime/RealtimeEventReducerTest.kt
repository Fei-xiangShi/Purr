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
        assertThat(result.incomingCallCandidate).isEqualTo(incomingCall())
    }

    @Test
    fun `call created by current user is not treated as incoming`() {
        val result = RealtimeState(incomingCallCandidate = incomingCall()).applyRealtimeEvent(
            event = incomingEvent(type = "call_started", callerUserId = "user-a"),
            currentUserId = "user-a",
        )

        assertThat(result.incomingCallCandidate).isNull()
        assertThat(result.activeCall?.isIncoming).isFalse()
        assertThat(result.activeCall?.callId).isEqualTo("call-1")
    }

    @Test
    fun `presence event preserves incoming call`() {
        val result = RealtimeState(incomingCallCandidate = incomingCall()).applyRealtimeEvent(
            event = RealtimeEventDto(type = "presence_changed", partnerOnline = false),
            currentUserId = "user-a",
        )

        assertThat(result.partnerOnline).isFalse()
        assertThat(result.incomingCallCandidate).isEqualTo(incomingCall())
    }

    @Test
    fun `call ended only clears matching incoming call`() {
        val state = RealtimeState(incomingCallCandidate = incomingCall())

        val unrelated = state.applyRealtimeEvent(
            event = RealtimeEventDto(type = "call_ended", callId = "call-2"),
            currentUserId = "user-a",
        )
        val matching = state.applyRealtimeEvent(
            event = RealtimeEventDto(type = "call_ended", callId = "call-1"),
            currentUserId = "user-a",
        )

        assertThat(unrelated.incomingCallCandidate).isEqualTo(incomingCall())
        assertThat(matching.incomingCallCandidate).isNull()
    }

    @Test
    fun `active call fallback only restores incoming calls`() {
        val incoming = activeCall(isIncoming = true).toIncomingCallOrNull()
        val outgoing = activeCall(isIncoming = false).toIncomingCallOrNull()

        assertThat(incoming).isEqualTo(incomingCall())
        assertThat(outgoing).isNull()
    }

    @Test
    fun `recovery poll cannot restore a consumed incoming call`() {
        val registry = ConsumedIncomingCallRegistry()
        registry.markConsumed("call-1")

        val restored = activeCall(isIncoming = true).toIncomingCallOrNull(registry::contains)

        assertThat(restored).isNull()
    }

    @Test
    fun `consumed call cannot be resurrected by stale realtime messages`() {
        val registry = ConsumedIncomingCallRegistry()
        registry.markConsumed("call-1")

        val fromStartedEvent = RealtimeState().applyRealtimeEvent(
            event = incomingEvent(type = "call_started"),
            currentUserId = "user-a",
            isConsumed = registry::contains,
        )
        val fromSnapshot = RealtimeState().applyRealtimeEvent(
            event = incomingEvent(type = "snapshot"),
            currentUserId = "user-a",
            isConsumed = registry::contains,
        )

        assertThat(fromStartedEvent.incomingCallCandidate).isNull()
        assertThat(fromSnapshot.incomingCallCandidate).isNull()
    }

    @Test
    fun `consuming one call does not suppress a later call`() {
        val registry = ConsumedIncomingCallRegistry()
        registry.markConsumed("call-1")

        val result = RealtimeState().applyRealtimeEvent(
            event = incomingEvent(type = "call_started").copy(callId = "call-2"),
            currentUserId = "user-a",
            isConsumed = registry::contains,
        )

        assertThat(result.incomingCallCandidate?.callId).isEqualTo("call-2")
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
