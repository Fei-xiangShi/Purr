package life.fxs.purr.feature.settings.avatar

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class AvatarCropGeometryTest {
    @Test
    fun `projects a centered landscape crop using the shortest side`() {
        val projection = AvatarCropGeometry.project(
            imageWidth = 2_000,
            imageHeight = 1_000,
            viewportDiameter = 500f,
            transform = AvatarPreviewTransform(),
        )

        assertThat(projection.cropArea).isEqualTo(AvatarCropArea(500f, 0f, 1_000f))
        assertThat(projection.offsetX).isZero()
        assertThat(projection.offsetY).isZero()
        assertThat(projection.maxOffsetX).isEqualTo(250f)
        assertThat(projection.maxOffsetY).isZero()
    }

    @Test
    fun `clamps preview offsets so the crop never leaves the source`() {
        val projection = AvatarCropGeometry.project(
            imageWidth = 2_000,
            imageHeight = 1_000,
            viewportDiameter = 500f,
            transform = AvatarPreviewTransform(zoom = 2f, offsetX = 10_000f, offsetY = -10_000f),
        )

        assertThat(projection.offsetX).isEqualTo(projection.maxOffsetX)
        assertThat(projection.offsetY).isEqualTo(-projection.maxOffsetY)
        projection.cropArea.requireValidFor(2_000, 1_000)
        assertThat(projection.cropArea.right).isEqualTo(500f)
        assertThat(projection.cropArea.top).isEqualTo(500f)
    }

    @Test
    fun `projects a centered portrait crop using the shortest side`() {
        val projection = AvatarCropGeometry.project(
            imageWidth = 1_000,
            imageHeight = 2_000,
            viewportDiameter = 400f,
            transform = AvatarPreviewTransform(),
        )

        assertThat(projection.cropArea).isEqualTo(AvatarCropArea(0f, 500f, 1_000f))
        assertThat(projection.maxOffsetX).isZero()
        assertThat(projection.maxOffsetY).isEqualTo(200f)
    }

    @Test
    fun `rejects invalid preview inputs`() {
        val invalidZoom = AvatarPreviewTransform(zoom = 0.9f)
        val exception = kotlin.runCatching {
            AvatarCropGeometry.project(100, 100, 50f, invalidZoom)
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `keeps projected area within bounds across varied dimensions and gestures`() {
        val random = Random(42)
        repeat(500) {
            val width = random.nextInt(1, 4_000)
            val height = random.nextInt(1, 4_000)
            val viewport = random.nextDouble(1.0, 800.0).toFloat()
            val transform = AvatarPreviewTransform(
                zoom = random.nextDouble(1.0, 5.0).toFloat(),
                offsetX = random.nextDouble(-2_000.0, 2_000.0).toFloat(),
                offsetY = random.nextDouble(-2_000.0, 2_000.0).toFloat(),
            )

            AvatarCropGeometry.project(width, height, viewport, transform)
                .cropArea
                .requireValidFor(width, height)
        }
    }
}
