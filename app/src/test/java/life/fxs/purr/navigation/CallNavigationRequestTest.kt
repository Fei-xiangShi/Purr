package life.fxs.purr.navigation

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.CallDirection
import org.junit.Test

class CallNavigationRequestTest {
    @Test
    fun `same pair submitted twice creates two consumable events`() {
        val store = CallNavigationRequestStore()

        val first = store.submit("pair-1", CallDirection.Outgoing, "call-1")
        val second = store.submit("pair-1", CallDirection.Outgoing, "call-1")

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(second!!.requestId).isNotEqualTo(first!!.requestId)
        assertThat(store.pending).isEqualTo(second)
    }

    @Test
    fun `consuming an old request cannot discard a newer request`() {
        val store = CallNavigationRequestStore()
        val first = store.submit("pair-1", CallDirection.Outgoing, "call-1")!!
        val second = store.submit("pair-1", CallDirection.Outgoing, "call-1")!!

        assertThat(store.consume(first.requestId)).isFalse()
        assertThat(store.pending).isEqualTo(second)

        assertThat(store.consume(second.requestId)).isTrue()
        assertThat(store.pending).isNull()
    }

    @Test
    fun `blank external pair does not create a navigation request`() {
        val store = CallNavigationRequestStore()
        store.submit("pair-1", CallDirection.Outgoing, "call-1")

        assertThat(store.submit("  ", CallDirection.Outgoing, "call-1")).isNull()
        assertThat(store.pending).isNull()
    }

    @Test
    fun `incoming external request preserves direction`() {
        val store = CallNavigationRequestStore()

        val request = store.submit("pair-1", CallDirection.Incoming, "call-1")

        assertThat(request?.direction).isEqualTo(CallDirection.Incoming)
    }

    @Test
    fun `incoming external request preserves expected call identity`() {
        val store = CallNavigationRequestStore()

        val request = store.submit(
            pairId = "pair-1",
            direction = CallDirection.Incoming,
            callId = "call-1",
        )

        assertThat(request?.callId).isEqualTo("call-1")
    }
}
