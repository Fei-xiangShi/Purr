package life.fxs.purr.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallOverlayVisibilityStoreTest {
    @Test
    fun `overlay is hidden only while the foreground call surface is visible`() {
        val store = CallOverlayVisibilityStore()

        store.setApplicationForeground(true)
        store.setCallSurfaceVisible(true)
        assertThat(store.state.value.shouldShowOverlay).isFalse()

        store.setCallSurfaceVisible(false)
        assertThat(store.state.value.shouldShowOverlay).isTrue()

        store.setCallSurfaceVisible(true)
        store.setApplicationForeground(false)
        assertThat(store.state.value.shouldShowOverlay).isTrue()
    }
}
