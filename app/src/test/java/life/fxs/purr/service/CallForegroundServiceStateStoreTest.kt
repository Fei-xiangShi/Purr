package life.fxs.purr.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallForegroundServiceStateStoreTest {
    @Test
    fun `same call can be started idempotently`() {
        val store = CallForegroundServiceStateStore()

        assertThat(store.markStarted("call-1")).isTrue()
        assertThat(store.markStarted("call-1")).isTrue()
        assertThat(store.state.value.activeCallId).isEqualTo("call-1")
    }

    @Test
    fun `different call cannot replace active service owner`() {
        val store = CallForegroundServiceStateStore()

        store.markStarted("call-1")

        assertThat(store.markStarted("call-2")).isFalse()
        assertThat(store.state.value.activeCallId).isEqualTo("call-1")
    }

    @Test
    fun `stopping a different call does not clear active owner`() {
        val store = CallForegroundServiceStateStore()

        store.markStarted("call-1")
        store.markStopped("call-2")

        assertThat(store.state.value.activeCallId).isEqualTo("call-1")
        assertThat(store.isActiveFor("call-1")).isTrue()
    }

    @Test
    fun `null stop clears state when service is destroyed`() {
        val store = CallForegroundServiceStateStore()

        store.markStarted("call-1")
        store.markStopped(null)

        assertThat(store.state.value.isActive).isFalse()
        assertThat(store.isActiveFor("call-1")).isFalse()
    }
}
