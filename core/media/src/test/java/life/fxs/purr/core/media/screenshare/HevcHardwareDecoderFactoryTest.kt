package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.VideoDecoder
import livekit.org.webrtc.VideoDecoderFactory
import org.junit.Test

class HevcHardwareDecoderFactoryTest {
    @Test
    fun `offer advertises only HEVC and preserves hardware codec parameters`() {
        val hevc = VideoCodecInfo("H265", mapOf("profile-id" to "1"), emptyList())
        val factory = HevcHardwareDecoderFactory(FakeHardwareFactory(codec("VP8"), hevc, codec("H264")))
        assertThat(factory.supportedCodecs.toList()).containsExactly(hevc)
        assertThat(factory.supportedCodecs.single().params).containsEntry("profile-id", "1")
    }

    @Test
    fun `missing hardware HEVC does not advertise or create an alternative decoder`() {
        val hardware = FakeHardwareFactory(codec("H264"))
        val factory = HevcHardwareDecoderFactory(hardware)
        assertThat(factory.supportedCodecs).isEmpty()
        assertThat(factory.createDecoder(codec("H265"))).isNull()
        assertThat(factory.createDecoder(codec("H264"))).isNull()
        assertThat(hardware.requests).isEmpty()
    }

    @Test
    fun `unexpected codec is rejected before reaching the decoder`() {
        val hardware = FakeHardwareFactory(codec("H265"), codec("H264"))
        val factory = HevcHardwareDecoderFactory(hardware)
        assertThat(factory.createDecoder(codec("VP9"))).isNull()
        assertThat(factory.createDecoder(codec("H264"))).isNull()
        assertThat(hardware.requests).isEmpty()
    }

    @Test
    fun `hardware creation failure propagates without another codec or software fallback`() {
        val hardware = FakeHardwareFactory(codec("H265"))
        val factory = HevcHardwareDecoderFactory(hardware)
        val requestedCodec = codec("H265")
        assertThat(factory.createDecoder(requestedCodec)).isNull()
        assertThat(hardware.requests).containsExactly(requestedCodec)
    }

    private class FakeHardwareFactory(vararg val codecs: VideoCodecInfo) : VideoDecoderFactory {
        val requests = mutableListOf<VideoCodecInfo>()
        override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(*codecs)
        override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
            requests += codecInfo
            return null
        }
    }

    private fun codec(name: String) = VideoCodecInfo(name, emptyMap(), emptyList())
}
