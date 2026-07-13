package life.fxs.purr.feature.settings.avatar

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

data class AvatarDecodeOptions(
    val maxDecodedDimensionPx: Int = DEFAULT_MAX_DECODED_DIMENSION_PX,
    val maxEncodedBytes: Long = DEFAULT_MAX_ENCODED_BYTES,
    val maxSourcePixels: Long = DEFAULT_MAX_SOURCE_PIXELS,
) {
    init {
        require(maxDecodedDimensionPx > 0) { "Maximum decoded dimension must be positive" }
        require(maxEncodedBytes > 0) { "Maximum encoded size must be positive" }
        require(maxSourcePixels > 0) { "Maximum source pixel count must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_DECODED_DIMENSION_PX = 2_048
        const val DEFAULT_MAX_ENCODED_BYTES = 25L * 1024 * 1024
        const val DEFAULT_MAX_SOURCE_PIXELS = 250_000_000L
    }
}

class AvatarImageDecoder(
    private val contentResolver: ContentResolver,
    private val options: AvatarDecodeOptions = AvatarDecodeOptions(),
) {
    @Throws(AvatarImageProcessingException::class)
    fun decode(uri: Uri): DecodedAvatarImage {
        try {
            rejectKnownOversizedSource(uri)
            val bounds = decodeBounds(uri)
            val sourcePixels = bounds.first.toLong() * bounds.second
            if (sourcePixels > options.maxSourcePixels) {
                throw AvatarImageProcessingException(
                    AvatarImageFailure.SOURCE_TOO_LARGE,
                    "Source image dimensions are too large",
                )
            }

            val sampleSize = calculateSampleSize(
                width = bounds.first,
                height = bounds.second,
                maxDimension = options.maxDecodedDimensionPx,
            )
            val decoded = openBoundedStream(uri).use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    },
                )
            } ?: throw AvatarImageProcessingException(
                AvatarImageFailure.INVALID_IMAGE,
                "The selected file is not a decodable image",
            )

            val orientation = readOrientation(uri)
            val oriented = try {
                applyExifOrientation(decoded, orientation)
            } catch (throwable: RuntimeException) {
                decoded.recycle()
                throw throwable
            }
            if (oriented !== decoded) decoded.recycle()

            return DecodedAvatarImage(
                bitmap = oriented,
                encodedWidth = bounds.first,
                encodedHeight = bounds.second,
                sampleSize = sampleSize,
            )
        } catch (exception: AvatarImageProcessingException) {
            throw exception
        } catch (exception: EncodedImageTooLargeException) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.SOURCE_TOO_LARGE,
                "The selected image file is too large",
                exception,
            )
        } catch (exception: SecurityException) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.CANNOT_READ,
                "Permission to read the selected image was denied",
                exception,
            )
        } catch (exception: IOException) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.CANNOT_READ,
                "The selected image could not be read",
                exception,
            )
        } catch (exception: RuntimeException) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.INVALID_IMAGE,
                "The selected file is not a valid image",
                exception,
            )
        }
    }

    private fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val decodeOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openBoundedStream(uri).use { input -> BitmapFactory.decodeStream(input, null, decodeOptions) }
        if (decodeOptions.outWidth <= 0 || decodeOptions.outHeight <= 0) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.INVALID_IMAGE,
                "The selected file does not contain valid image dimensions",
            )
        }
        return decodeOptions.outWidth to decodeOptions.outHeight
    }

    private fun readOrientation(uri: Uri): Int = try {
        openBoundedStream(uri).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (_: RuntimeException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rejectKnownOversizedSource(uri: Uri) {
        val length = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return
        if (length > options.maxEncodedBytes) throw EncodedImageTooLargeException()
    }

    private fun openBoundedStream(uri: Uri): InputStream {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Content resolver returned no stream")
        return BufferedInputStream(LimitedInputStream(input, options.maxEncodedBytes))
    }

    internal companion object {
        fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
            var sampleSize = 1
            while (
                ceilDiv(width, sampleSize) > maxDimension ||
                ceilDiv(height, sampleSize) > maxDimension
            ) {
                check(sampleSize <= Int.MAX_VALUE / 2) { "Image dimensions are unsupported" }
                sampleSize *= 2
            }
            return sampleSize
        }

        fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    matrix.setRotate(180f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> return source
            }
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }

        private fun ceilDiv(value: Int, divisor: Int): Int =
            (value.toLong() + divisor - 1).div(divisor).toInt()
    }
}

private class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        if (consumed >= limit) {
            if (super.read() == -1) return -1
            throw EncodedImageTooLargeException()
        }
        return super.read().also { if (it != -1) consumed++ }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = limit - consumed
        if (remaining <= 0) return read().let { if (it == -1) -1 else error("unreachable") }
        val read = super.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) consumed += read
        return read
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        val remaining = limit - consumed
        if (remaining <= 0) return if (read() == -1) 0 else error("unreachable")
        return super.skip(minOf(byteCount, remaining)).also { consumed += it }
    }

    override fun available(): Int = minOf(super.available().toLong(), limit - consumed)
        .coerceAtLeast(0)
        .toInt()
}

private class EncodedImageTooLargeException : IOException()
