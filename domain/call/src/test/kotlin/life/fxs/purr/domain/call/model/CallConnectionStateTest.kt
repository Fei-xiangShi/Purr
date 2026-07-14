package life.fxs.purr.domain.call.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallConnectionStateTest {
    @Test
    fun `ongoing and terminal state groups are explicit and disjoint`() {
        val ongoing = listOf(
            CallConnectionState.Preparing,
            CallConnectionState.Connecting,
            CallConnectionState.Connected,
            CallConnectionState.Reconnecting,
            CallConnectionState.Terminating,
        )
        val inactive = listOf(
            CallConnectionState.Idle,
            CallConnectionState.Disconnected,
            CallConnectionState.Failed(),
        )

        assertThat(ongoing.all(CallConnectionState::isOngoing)).isTrue()
        assertThat(ongoing.any(CallConnectionState::isTerminal)).isFalse()
        assertThat(inactive.any(CallConnectionState::isOngoing)).isFalse()
        assertThat(CallConnectionState.Disconnected.isTerminal).isTrue()
        assertThat(CallConnectionState.Failed().isTerminal).isTrue()
        assertThat(CallConnectionState.Idle.isTerminal).isFalse()
    }
}
