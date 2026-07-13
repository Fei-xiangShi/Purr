package life.fxs.purr.feature.settings.avatar

import android.graphics.Bitmap
import android.graphics.Color

/** Owns [bitmap]; call [close] after the preview and any export have finished. */
data class DecodedAvatarImage(
    val bitmap: Bitmap,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val sampleSize: Int,
) : AutoCloseable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

enum class AvatarOutputFormat(val contentType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
}

data class AvatarExportOptions(
    val outputSizePx: Int = DEFAULT_OUTPUT_SIZE_PX,
    val format: AvatarOutputFormat = AvatarOutputFormat.JPEG,
    val quality: Int = DEFAULT_JPEG_QUALITY,
    val jpegBackgroundColor: Int = Color.WHITE,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(outputSizePx in MIN_OUTPUT_SIZE_PX..MAX_OUTPUT_SIZE_PX) {
            "Output size must be between $MIN_OUTPUT_SIZE_PX and $MAX_OUTPUT_SIZE_PX pixels"
        }
        require(quality in 0..100) { "Quality must be between 0 and 100" }
        require(maxOutputBytes > 0) { "Maximum output size must be positive" }
        require(format != AvatarOutputFormat.JPEG || Color.alpha(jpegBackgroundColor) == 255) {
            "JPEG background must be opaque"
        }
    }

    companion object {
        const val DEFAULT_OUTPUT_SIZE_PX = 512
        const val DEFAULT_JPEG_QUALITY = 90
        const val DEFAULT_MAX_OUTPUT_BYTES = 10 * 1024 * 1024
        const val MIN_OUTPUT_SIZE_PX = 64
        const val MAX_OUTPUT_SIZE_PX = 2_048
    }
}

data class AvatarImagePayload(
    val contentType: String,
    val bytes: ByteArray,
    val pixelSize: Int,
)

enum class AvatarImageFailure {
    CANNOT_READ,
    INVALID_IMAGE,
    SOURCE_TOO_LARGE,
    OUTPUT_TOO_LARGE,
    ENCODING_FAILED,
}

class AvatarImageProcessingException(
    val failure: AvatarImageFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
