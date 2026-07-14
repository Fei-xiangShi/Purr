package life.fxs.purr.data.call.audio

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import org.junit.Test

class MutableCallAudioLevelProviderTest {
    private val provider = MutableCallAudioLevelProvider()

    @Test
    fun `pcm callback publishes perceptual rms level without consuming buffer`() {
        val audioData = pcm16(8_192, 8_192, 8_192, 8_192)
        val initialPosition = audioData.position()

        provider.onData(
            audioData = audioData,
            bitsPerSample = 16,
            sampleRate = 48_000,
            numberOfChannels = 1,
            numberOfFrames = 4,
            absoluteCaptureTimestampMs = 1L,
        )

        assertThat(provider.localAudioLevel.value).isWithin(0.001f).of(0.5f)
        assertThat(audioData.position()).isEqualTo(initialPosition)
    }

    @Test
    fun `peak contribution preserves a short transient`() {
        provider.onData(
            audioData = pcm16(32_767, 0, 0, 0),
            bitsPerSample = 16,
            sampleRate = 48_000,
            numberOfChannels = 1,
            numberOfFrames = 4,
            absoluteCaptureTimestampMs = 1L,
        )

        assertThat(provider.localAudioLevel.value).isWithin(0.002f).of(0.707f)
    }

    @Test
    fun `full scale PCM and reset publish expected levels`() {
        provider.onData(
            audioData = pcm16(-32_768, -32_768),
            bitsPerSample = 16,
            sampleRate = 48_000,
            numberOfChannels = 1,
            numberOfFrames = 2,
            absoluteCaptureTimestampMs = 1L,
        )
        assertThat(provider.localAudioLevel.value).isEqualTo(1f)

        provider.reset()

        assertThat(provider.localAudioLevel.value).isEqualTo(0f)
    }

    @Test
    fun `unsupported pcm format cannot replace the latest valid sample`() {
        provider.onData(
            audioData = pcm16(8_192),
            bitsPerSample = 16,
            sampleRate = 48_000,
            numberOfChannels = 1,
            numberOfFrames = 1,
            absoluteCaptureTimestampMs = 1L,
        )
        val validLevel = provider.localAudioLevel.value

        provider.onData(
            audioData = ByteBuffer.wrap(byteArrayOf(0)),
            bitsPerSample = 8,
            sampleRate = 48_000,
            numberOfChannels = 1,
            numberOfFrames = 1,
            absoluteCaptureTimestampMs = 2L,
        )

        assertThat(provider.localAudioLevel.value).isEqualTo(validLevel)
    }

    @Test
    fun `remote sink publishes pcm levels independently and can be reset`() {
        provider.remoteAudioSink.onData(
            pcm16(16_384, 16_384),
            16,
            48_000,
            1,
            2,
            1L,
        )

        assertThat(provider.remoteAudioLevel.value).isWithin(0.001f).of(0.707f)
        assertThat(provider.localAudioLevel.value).isEqualTo(0f)

        provider.resetRemote()

        assertThat(provider.remoteAudioLevel.value).isEqualTo(0f)
    }

    private fun pcm16(vararg samples: Int): ByteBuffer = ByteBuffer
        .allocate(samples.size * 2)
        .apply {
            samples.forEach { sample ->
                put((sample and 0xFF).toByte())
                put(((sample shr 8) and 0xFF).toByte())
            }
            flip()
        }
}
