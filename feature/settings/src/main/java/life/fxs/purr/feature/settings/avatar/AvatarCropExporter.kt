package life.fxs.purr.feature.settings.avatar

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Renders synchronously and never recycles [source]; the caller must keep it alive for the call. */
class AvatarCropExporter {
    @Throws(AvatarImageProcessingException::class)
    fun export(
        source: Bitmap,
        cropArea: AvatarCropArea,
        options: AvatarExportOptions = AvatarExportOptions(),
    ): AvatarImagePayload {
        try {
            require(!source.isRecycled) { "Source bitmap has been recycled" }
            cropArea.requireValidFor(source.width, source.height)

            val output = Bitmap.createBitmap(
                options.outputSizePx,
                options.outputSizePx,
                Bitmap.Config.ARGB_8888,
            )
            try {
                drawCircularCrop(source, cropArea, output, options)
                val bytes = compress(output, options)
                return AvatarImagePayload(
                    contentType = options.format.contentType,
                    bytes = bytes,
                    pixelSize = options.outputSizePx,
                )
            } finally {
                output.recycle()
            }
        } catch (exception: AvatarImageProcessingException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.ENCODING_FAILED,
                "The avatar crop could not be rendered",
                exception,
            )
        }
    }

    private fun drawCircularCrop(
        source: Bitmap,
        cropArea: AvatarCropArea,
        output: Bitmap,
        options: AvatarExportOptions,
    ) {
        val canvas = Canvas(output)
        if (options.format == AvatarOutputFormat.JPEG) {
            canvas.drawColor(options.jpegBackgroundColor)
        }

        val sourceRect = RectF(cropArea.left, cropArea.top, cropArea.right, cropArea.bottom)
        val outputRect = RectF(0f, 0f, output.width.toFloat(), output.height.toFloat())
        val shaderMatrix = Matrix().apply {
            setRectToRect(sourceRect, outputRect, Matrix.ScaleToFit.FILL)
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(shaderMatrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            this.shader = shader
        }
        val radius = output.width / 2f
        canvas.drawCircle(radius, radius, radius, paint)
    }

    private fun compress(output: Bitmap, options: AvatarExportOptions): ByteArray {
        val bytes = BoundedByteArrayOutputStream(options.maxOutputBytes)
        val format = when (options.format) {
            AvatarOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            AvatarOutputFormat.PNG -> Bitmap.CompressFormat.PNG
        }
        val compressed = try {
            output.compress(format, options.quality, bytes)
        } catch (exception: OutputLimitExceededException) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.OUTPUT_TOO_LARGE,
                "The encoded avatar exceeds the upload limit",
                exception,
            )
        }
        if (bytes.limitExceeded) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.OUTPUT_TOO_LARGE,
                "The encoded avatar exceeds the upload limit",
            )
        }
        if (!compressed) {
            throw AvatarImageProcessingException(
                AvatarImageFailure.ENCODING_FAILED,
                "The avatar encoder did not produce an image",
            )
        }
        return bytes.toByteArray()
    }
}

private class BoundedByteArrayOutputStream(private val limit: Int) : OutputStream() {
    private val delegate = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    private var count = 0
    var limitExceeded: Boolean = false
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        ensureCapacity(length)
        delegate.write(bytes, offset, length)
        count += length
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
        if (additionalBytes > limit - count) {
            limitExceeded = true
            throw OutputLimitExceededException()
        }
    }
}

private class OutputLimitExceededException : RuntimeException()
