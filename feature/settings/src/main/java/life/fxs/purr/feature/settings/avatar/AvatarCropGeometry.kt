package life.fxs.purr.feature.settings.avatar

import kotlin.math.max
import kotlin.math.min

/** A square crop in oriented source-bitmap pixels. */
data class AvatarCropArea(
    val left: Float,
    val top: Float,
    val diameter: Float,
) {
    val right: Float get() = left + diameter
    val bottom: Float get() = top + diameter

    fun requireValidFor(imageWidth: Int, imageHeight: Int) {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(left.isFinite() && top.isFinite() && diameter.isFinite()) {
            "Crop values must be finite"
        }
        require(diameter > 0f) { "Crop diameter must be positive" }
        require(left >= 0f && top >= 0f && right <= imageWidth && bottom <= imageHeight) {
            "Crop area must be inside the source image"
        }
    }

    companion object {
        fun centered(imageWidth: Int, imageHeight: Int): AvatarCropArea {
            require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
            val diameter = min(imageWidth, imageHeight).toFloat()
            return AvatarCropArea(
                left = (imageWidth - diameter) / 2f,
                top = (imageHeight - diameter) / 2f,
                diameter = diameter,
            )
        }
    }
}

/**
 * Preview transform where offsets describe the rendered image center relative to the crop center.
 * Positive X moves the image right; positive Y moves it down.
 */
data class AvatarPreviewTransform(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** Geometry shared by the preview gesture layer and the final exporter. */
data class AvatarCropProjection(
    val cropArea: AvatarCropArea,
    val renderedScale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val maxOffsetX: Float,
    val maxOffsetY: Float,
)

object AvatarCropGeometry {
    /**
     * Projects a circular preview onto the oriented source bitmap. Offsets outside the image are
     * clamped, and the clamped values are returned so the preview can use the exact same bounds.
     */
    fun project(
        imageWidth: Int,
        imageHeight: Int,
        viewportDiameter: Float,
        transform: AvatarPreviewTransform,
    ): AvatarCropProjection {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(viewportDiameter.isFinite() && viewportDiameter > 0f) {
            "Viewport diameter must be finite and positive"
        }
        require(transform.zoom.isFinite() && transform.zoom >= 1f) {
            "Zoom must be finite and at least 1"
        }
        require(transform.offsetX.isFinite() && transform.offsetY.isFinite()) {
            "Offsets must be finite"
        }

        val baseScale = max(
            viewportDiameter / imageWidth,
            viewportDiameter / imageHeight,
        )
        val renderedScale = baseScale * transform.zoom
        require(renderedScale.isFinite() && renderedScale > 0f) { "Rendered scale is invalid" }

        val maxOffsetX = max(0f, (imageWidth * renderedScale - viewportDiameter) / 2f)
        val maxOffsetY = max(0f, (imageHeight * renderedScale - viewportDiameter) / 2f)
        val offsetX = transform.offsetX.coerceIn(-maxOffsetX, maxOffsetX)
        val offsetY = transform.offsetY.coerceIn(-maxOffsetY, maxOffsetY)

        val diameter = min(
            viewportDiameter / renderedScale,
            min(imageWidth, imageHeight).toFloat(),
        )
        val maxLeft = imageWidth - diameter
        val maxTop = imageHeight - diameter
        val sourceCenterX = imageWidth / 2f - offsetX / renderedScale
        val sourceCenterY = imageHeight / 2f - offsetY / renderedScale
        val left = (sourceCenterX - diameter / 2f).coerceIn(0f, maxLeft)
        val top = (sourceCenterY - diameter / 2f).coerceIn(0f, maxTop)
        // Floating-point projection can put the far edge a fraction past the bitmap boundary.
        // Trim that fraction instead of rejecting an otherwise valid gesture state.
        val safeDiameter = min(diameter, min(imageWidth - left, imageHeight - top))
        val cropArea = AvatarCropArea(left = left, top = top, diameter = safeDiameter)
        cropArea.requireValidFor(imageWidth, imageHeight)

        return AvatarCropProjection(
            cropArea = cropArea,
            renderedScale = renderedScale,
            offsetX = offsetX,
            offsetY = offsetY,
            maxOffsetX = maxOffsetX,
            maxOffsetY = maxOffsetY,
        )
    }
}
