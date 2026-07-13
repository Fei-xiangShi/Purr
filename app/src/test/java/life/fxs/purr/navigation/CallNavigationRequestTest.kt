package life.fxs.purr.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallNavigationRequestTest {
    @Test
    fun `same pair submitted twice creates two consumable events`() {
        val store = CallNavigationRequestStore()

        val first = store.submit("pair-1")
        val second = store.submit("pair-1")

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(second!!.requestId).isNotEqualTo(first!!.requestId)
        assertThat(store.pending).isEqualTo(second)
    }

    @Test
    fun `consuming an old request cannot discard a newer request`() {
        val store = CallNavigationRequestStore()
        val first = store.submit("pair-1")!!
        val second = store.submit("pair-1")!!

        assertThat(store.consume(first.requestId)).isFalse()
        assertThat(store.pending).isEqualTo(second)

        assertThat(store.consume(second.requestId)).isTrue()
        assertThat(store.pending).isNull()
    }

    @Test
    fun `blank external pair does not create a navigation request`() {
        val store = CallNavigationRequestStore()
        store.submit("pair-1")

        assertThat(store.submit("  ")).isNull()
        assertThat(store.pending).isNull()
    }
}
