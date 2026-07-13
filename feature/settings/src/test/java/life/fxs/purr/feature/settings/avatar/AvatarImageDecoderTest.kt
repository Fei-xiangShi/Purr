package life.fxs.purr.feature.settings.avatar

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarImageDecoderTest {
    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @Test
    fun `applies exif rotation while decoding`() {
        val file = temporaryJpeg(width = 40, height = 20)
        ExifInterface(file.absolutePath).also {
            it.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            it.saveAttributes()
        }

        val result = AvatarImageDecoder(resolver).decode(Uri.fromFile(file))

        assertThat(result.encodedWidth).isEqualTo(40)
        assertThat(result.encodedHeight).isEqualTo(20)
        assertThat(result.bitmap.width).isEqualTo(20)
        assertThat(result.bitmap.height).isEqualTo(40)
        result.bitmap.recycle()
        check(file.delete())
    }

    @Test
    fun `samples large source before allocating bitmap`() {
        val file = temporaryPng(width = 400, height = 200)

        val result = AvatarImageDecoder(
            resolver,
            AvatarDecodeOptions(maxDecodedDimensionPx = 100),
        ).decode(Uri.fromFile(file))

        assertThat(result.sampleSize).isEqualTo(4)
        assertThat(result.bitmap.width).isEqualTo(100)
        assertThat(result.bitmap.height).isEqualTo(50)
        result.bitmap.recycle()
        check(file.delete())
    }

    @Test
    fun `rejects undecodable files`() {
        val file = File.createTempFile("avatar-invalid", ".bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val exception = kotlin.runCatching {
            AvatarImageDecoder(resolver).decode(Uri.fromFile(file))
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(AvatarImageProcessingException::class.java)
        assertThat((exception as AvatarImageProcessingException).failure)
            .isEqualTo(AvatarImageFailure.INVALID_IMAGE)
        check(file.delete())
    }

    @Test
    fun `rejects sources over configured pixel budget before full decode`() {
        val file = temporaryPng(width = 400, height = 200)

        val exception = kotlin.runCatching {
            AvatarImageDecoder(
                resolver,
                AvatarDecodeOptions(maxSourcePixels = 1_000),
            ).decode(Uri.fromFile(file))
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(AvatarImageProcessingException::class.java)
        assertThat((exception as AvatarImageProcessingException).failure)
            .isEqualTo(AvatarImageFailure.SOURCE_TOO_LARGE)
        check(file.delete())
    }

    private fun temporaryPng(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return temporaryImage(bitmap, CompressFormat.PNG)
    }

    private fun temporaryJpeg(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return temporaryImage(bitmap, CompressFormat.JPEG)
    }

    private fun temporaryImage(bitmap: Bitmap, format: CompressFormat): File {
        val file = File.createTempFile("avatar", ".image")
        FileOutputStream(file).use { output -> check(bitmap.compress(format, 100, output)) }
        bitmap.recycle()
        return file
    }
}
