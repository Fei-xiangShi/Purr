package life.fxs.purr.core.media.screenshare

import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.VideoDecoder
import livekit.org.webrtc.VideoDecoderFactory

/** Advertise only HEVC from a hardware-only delegate; never introduce software fallback. */
internal class HevcHardwareDecoderFactory(
    private val hardwareFactory: VideoDecoderFactory,
) : VideoDecoderFactory {
    private val codecs = hardwareFactory.supportedCodecs
        .filter { it.name.equals("H265", ignoreCase = true) }
        .toTypedArray()

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = codecs.copyOf()

    override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
        if (codecs.isEmpty() || !codecInfo.name.equals("H265", ignoreCase = true)) return null
        return hardwareFactory.createDecoder(codecInfo)
    }
}
