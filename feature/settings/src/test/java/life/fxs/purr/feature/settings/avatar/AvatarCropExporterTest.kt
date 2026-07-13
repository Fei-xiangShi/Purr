package life.fxs.purr.feature.settings.avatar

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarCropExporterTest {
    private val exporter = AvatarCropExporter()

    @Test
    fun `png export keeps circular corners transparent`() {
        val source = solidBitmap(Color.RED)

        val payload = exporter.export(
            source = source,
            cropArea = AvatarCropArea.centered(source.width, source.height),
            options = AvatarExportOptions(outputSizePx = 64, format = AvatarOutputFormat.PNG),
        )

        val decoded = decode(payload.bytes)
        assertThat(payload.contentType).isEqualTo("image/png")
        assertThat(decoded.width).isEqualTo(64)
        assertThat(Color.alpha(decoded.getPixel(0, 0))).isEqualTo(0)
        assertThat(Color.red(decoded.getPixel(32, 32))).isGreaterThan(240)
        decoded.recycle()
        source.recycle()
    }

    @Test
    fun `jpeg export fills outside circle with configured background`() {
        val source = solidBitmap(Color.BLUE)

        val payload = exporter.export(
            source = source,
            cropArea = AvatarCropArea.centered(source.width, source.height),
            options = AvatarExportOptions(
                outputSizePx = 64,
                format = AvatarOutputFormat.JPEG,
                quality = 95,
                jpegBackgroundColor = Color.GREEN,
            ),
        )

        val decoded = decode(payload.bytes)
        assertThat(payload.contentType).isEqualTo("image/jpeg")
        assertThat(decoded.width).isEqualTo(64)
        assertThat(Color.green(decoded.getPixel(0, 0))).isGreaterThan(180)
        assertThat(Color.blue(decoded.getPixel(32, 32))).isGreaterThan(180)
        decoded.recycle()
        source.recycle()
    }

    @Test
    fun `export maps the selected source square into the output circle`() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                source.setPixel(x, y, if (x < 4 && y < 4) Color.RED else Color.BLUE)
            }
        }

        val payload = exporter.export(
            source = source,
            cropArea = AvatarCropArea(left = 0f, top = 0f, diameter = 4f),
            options = AvatarExportOptions(outputSizePx = 64, format = AvatarOutputFormat.PNG),
        )

        val decoded = decode(payload.bytes)
        assertThat(Color.red(decoded.getPixel(32, 32))).isGreaterThan(240)
        assertThat(Color.alpha(decoded.getPixel(32, 32))).isEqualTo(255)
        decoded.recycle()
        source.recycle()
    }

    @Test
    fun `export rejects crop areas outside source bounds`() {
        val source = solidBitmap(Color.RED)

        val exception = kotlin.runCatching {
            exporter.export(source, AvatarCropArea(left = 6f, top = 0f, diameter = 3f))
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
        source.recycle()
    }

    @Test
    fun `export rejects a recycled source bitmap`() {
        val source = solidBitmap(Color.RED)
        source.recycle()

        val exception = kotlin.runCatching {
            exporter.export(source, AvatarCropArea(0f, 0f, 1f))
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `jpeg options require an opaque background`() {
        val exception = kotlin.runCatching {
            AvatarExportOptions(
                format = AvatarOutputFormat.JPEG,
                jpegBackgroundColor = Color.TRANSPARENT,
            )
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `export enforces encoded output limit`() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val exception = kotlin.runCatching {
            exporter.export(
                source,
                AvatarCropArea.centered(source.width, source.height),
                AvatarExportOptions(outputSizePx = 64, format = AvatarOutputFormat.PNG, maxOutputBytes = 8),
            )
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(AvatarImageProcessingException::class.java)
        assertThat((exception as AvatarImageProcessingException).failure)
            .isEqualTo(AvatarImageFailure.OUTPUT_TOO_LARGE)
        source.recycle()
    }

    private fun solidBitmap(color: Int): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(color)
    }

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactoryCompat.decode(bytes)
}

private object BitmapFactoryCompat {
    fun decode(bytes: ByteArray): Bitmap {
        val options = android.graphics.BitmapFactory.Options()
        return checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    }
}
