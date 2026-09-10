package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenSharePublisherStateStoreTest {
    private val store = ScreenSharePublisherStateStore()
    private val request = ScreenSharePublishRequest(
        callId = "call-1",
        shareId = "share-1",
        whipUrl = "https://media.test/whip",
        bearerToken = "token",
        expiresAtEpochMillis = 60_000L,
    )

    @Test
    fun `late success cannot revive stopping publisher and old share cannot clear replacement`() {
        store.connecting(request)
        store.stopping(request)
        store.live(request)
        assertThat(store.status.value).isEqualTo(ScreenSharePublisherStatus.Stopping(request))
        val next = request.copy(shareId = "next")
        store.connecting(next)
        store.idle(request.callId, request.shareId)
        assertThat(store.status.value).isEqualTo(ScreenSharePublisherStatus.Connecting(next))
    }

    @Test
    fun `publisher lifecycle keeps the active request`() {
        store.requestingPermission(request)
        assertThat(store.status.value)
            .isEqualTo(ScreenSharePublisherStatus.RequestingPermission(request))

        store.connecting(request)
        store.live(request)
        assertThat(store.status.value).isEqualTo(ScreenSharePublisherStatus.Live(request))

        store.stopping(request)
        assertThat(store.status.value).isEqualTo(ScreenSharePublisherStatus.Stopping(request))
    }

    @Test
    fun `stale callbacks cannot replace another active share`() {
        store.connecting(request)

        store.live(request.copy(shareId = "share-stale"))
        store.failed(callId = "call-1", shareId = "share-stale", message = "stale")

        assertThat(store.status.value).isEqualTo(ScreenSharePublisherStatus.Connecting(request))
    }

    @Test
    fun `permission failure remains visible until matching call is cleared`() {
        store.failed(callId = "call-1", shareId = "share-1", message = "permission denied")

        store.idle(callId = "call-2")
        assertThat(store.status.value).isEqualTo(
            ScreenSharePublisherStatus.Failed("call-1", "share-1", "permission denied"),
        )

        store.idle(callId = "call-1")
        assertThat(store.status.value).isEqualTo(ScreenSharePublisherStatus.Idle)
    }
}
