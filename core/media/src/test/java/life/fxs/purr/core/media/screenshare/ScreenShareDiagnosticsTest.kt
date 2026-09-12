package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport

class ScreenShareDiagnosticsTest {
    @Test fun `receiver reports frame deltas not RTP packet count or lifetime averages`() {
        val collector = WhepQualityCollector { 2_000L }
        fun report(time: Long, frames: Int, packets: Int, lost: Int, bytes: Int, decode: Double) = RTCStatsReport(time,
            mapOf("video" to RTCStats(time, "inbound-rtp", "video", mapOf(
                "kind" to "video", "framesDecoded" to frames, "packetsReceived" to packets,
                "packetsLost" to lost, "bytesReceived" to bytes, "totalDecodeTime" to decode,
            ))))
        assertThat(collector.collect(report(1_000_000, 300, 1_000, 10, 100_000, 1.0)).framesPerSecond).isNull()
        val measured = collector.collect(report(2_000_000, 360, 1_990, 20, 1_100_000, 1.12))
        assertThat(measured.framesPerSecond).isEqualTo(60.0)
        assertThat(measured.bitrateKbps).isEqualTo(8_000.0)
        assertThat(measured.packetLossPercent).isEqualTo(1.0)
        assertThat(measured.decodeTimeMs).isWithin(0.001).of(2.0)
        assertThat(collector.collect(report(3_000_000, 1, 1, 0, 100, 0.01)).framesPerSecond).isNull()
    }
    @Test fun `old attempt cannot publish metrics or clear a replacement`() {
        val store = ScreenShareDiagnosticsStore()
        val old = store.beginReceiving("call", "share")
        val current = store.beginReceiving("call", "share")
        store.receive(old, ScreenShareQualitySample(1, framesPerSecond = 10.0))
        store.stopReceiving(old)
        assertThat(store.receiving.value?.sample).isNull()
        store.receive(current, ScreenShareQualitySample(2, framesPerSecond = 60.0))
        assertThat(store.receiving.value?.sample?.framesPerSecond).isEqualTo(60.0)
        store.stopReceiving(current)
        assertThat(store.receiving.value).isNull()
    }

    @Test fun `counter reset missing sample and zero interval remain unavailable`() {
        assertThat(counterDelta(4.0, 10.0)).isNull()
        assertThat(counterDelta(4.0, null)).isNull()
        assertThat(counterDelta(4.0, 4.0)).isEqualTo(0.0)
        assertThat(measuredRatio(2.0, 0.0)).isNull()
        assertThat(measuredRatio(0.0, 1.0)).isEqualTo(0.0)
        assertThat(measuredRatio(Double.NaN, 1.0)).isNull()
    }

    @Test fun `browser link contains read credential only in fragment and rejects publisher endpoints`() {
        val link = requireNotNull(browserWatchLink("https://stream.example/screen-share-123/whep", "read.token", 10_000)).toHttpUrl()
        assertThat(link.encodedPath).isEqualTo("/watch")
        assertThat(link.query).isNull()
        assertThat(link.fragment).contains("token=read.token")
        assertThat(link.newBuilder().fragment(null).build().toString()).doesNotContain("read.token")
        assertThat(browserWatchLink("https://stream.example/screen-share-123/whip", "publish-token", 10)).isNull()
        assertThat(browserWatchLink("http://stream.example/screen-share-123/whep", "read", 10)).isNull()
    }
}
