package life.fxs.purr.data.call.audio

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Calculates a perceptual display level from a 16-bit little-endian PCM frame.
 *
 * RMS represents sustained energy while the peak contribution preserves short transients.
 * The square-root mapping makes speech visible without changing the underlying audio stream.
 */
internal fun calculatePcmDisplayLevel(
    audioData: ByteBuffer,
    bitsPerSample: Int,
    numberOfChannels: Int,
    numberOfFrames: Int,
): Float? {
    if (bitsPerSample != PCM_16_BITS || numberOfChannels <= 0 || numberOfFrames <= 0) return null

    val expectedSamples = (numberOfFrames.toLong() * numberOfChannels)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val sampleCount = minOf(expectedSamples, audioData.remaining() / PCM_16_BYTES)
    if (sampleCount == 0) return 0f

    val firstByteIndex = audioData.position()
    var sumOfSquares = 0.0
    var peak = 0.0
    repeat(sampleCount) { sampleIndex ->
        val byteIndex = firstByteIndex + sampleIndex * PCM_16_BYTES
        val lowByte = audioData.get(byteIndex).toInt() and 0xFF
        val highByte = audioData.get(byteIndex + 1).toInt()
        val sample = ((highByte shl 8) or lowByte).toShort().toInt()
        val normalized = sample.toDouble() / PCM_16_SCALE
        sumOfSquares += normalized * normalized
        peak = max(peak, abs(normalized))
    }

    val rms = sqrt(sumOfSquares / sampleCount)
    val transientAwareLevel = max(rms, peak * PEAK_CONTRIBUTION)
    return sqrt(transientAwareLevel).toFloat().coerceIn(0f, 1f)
}

private const val PCM_16_BITS = 16
private const val PCM_16_BYTES = 2
private const val PCM_16_SCALE = 32_768.0
private const val PEAK_CONTRIBUTION = 0.35
