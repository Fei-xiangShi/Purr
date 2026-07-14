package life.fxs.purr.data.account.realtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConsumedIncomingCallRegistryTest {
    @Test
    fun `registry evicts the oldest tombstone when capacity is reached`() {
        val registry = ConsumedIncomingCallRegistry(capacity = 2)

        registry.markConsumed("call-1")
        registry.markConsumed("call-2")
        registry.markConsumed("call-3")

        assertThat(registry.contains("call-1")).isFalse()
        assertThat(registry.contains("call-2")).isTrue()
        assertThat(registry.contains("call-3")).isTrue()
    }

    @Test
    fun `remarking a tombstone refreshes its eviction order`() {
        val registry = ConsumedIncomingCallRegistry(capacity = 2)

        registry.markConsumed("call-1")
        registry.markConsumed("call-2")
        registry.markConsumed("call-1")
        registry.markConsumed("call-3")

        assertThat(registry.contains("call-1")).isTrue()
        assertThat(registry.contains("call-2")).isFalse()
        assertThat(registry.contains("call-3")).isTrue()
    }
}
