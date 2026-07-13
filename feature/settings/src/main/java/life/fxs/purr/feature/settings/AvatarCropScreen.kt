package life.fxs.purr.feature.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import life.fxs.purr.feature.settings.avatar.AvatarCropArea
import life.fxs.purr.feature.settings.avatar.AvatarCropGeometry
import life.fxs.purr.feature.settings.avatar.AvatarCropProjection
import life.fxs.purr.feature.settings.avatar.AvatarPreviewTransform
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_PREVIEW_ZOOM = 5f
private const val CROP_DIAMETER_FRACTION = 0.84f
private const val PAN_STEP_PX = 48f

/** Full-screen editor for selecting a circular region from an avatar source image. */
@Composable
internal fun AvatarCropScreen(
    bitmap: Bitmap,
    isExporting: Boolean,
    errorMessage: String? = null,
    onCancel: () -> Unit,
    onConfirm: (AvatarCropArea) -> Unit,
) {
    val cropState = remember(bitmap) {
        AvatarCropState(bitmap.width, bitmap.height)
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val projection = cropState.projection

    // The exporter owns the bitmap until it finishes; do not pop the destination mid-operation.
    BackHandler(enabled = isExporting) {}

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0C0B0E),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            CropScreenTopBar(
                isExporting = isExporting,
                canConfirm = projection != null,
                onCancel = onCancel,
                onConfirm = { projection?.cropArea?.let(onConfirm) },
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val viewportSize = minOf(maxWidth, maxHeight).coerceAtMost(600.dp)
                CropViewport(
                    bitmap = image,
                    state = cropState,
                    enabled = !isExporting,
                    modifier = Modifier.size(viewportSize),
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            CropZoomControls(
                zoom = cropState.zoom,
                enabled = !isExporting,
                errorMessage = errorMessage,
                onZoomChanged = cropState::updateZoom,
                onReset = cropState::reset,
            )
        }
    }
}

@Composable
private fun CropScreenTopBar(
    isExporting: Boolean,
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCancel,
            enabled = !isExporting,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "取消裁剪",
            )
        }
        Text(
            text = "裁剪头像",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
        )
        TextButton(
            onClick = onConfirm,
            enabled = canConfirm && !isExporting,
            modifier = Modifier.width(76.dp),
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(text = "使用")
            }
        }
    }
}

@Composable
private fun CropViewport(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    state: AvatarCropState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val zoomPercent = (state.zoom * 100f).roundToInt()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { state.updateViewport(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (enabled) {
                    Modifier.pointerInput(state) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            state.applyGesture(centroid, pan, zoomChange)
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = "头像裁剪预览"
                stateDescription = "缩放百分之$zoomPercent"
                customActions = if (enabled) listOf(
                    CustomAccessibilityAction("向左移动") {
                        state.panBy(-PAN_STEP_PX, 0f)
                        true
                    },
                    CustomAccessibilityAction("向右移动") {
                        state.panBy(PAN_STEP_PX, 0f)
                        true
                    },
                    CustomAccessibilityAction("向上移动") {
                        state.panBy(0f, -PAN_STEP_PX)
                        true
                    },
                    CustomAccessibilityAction("向下移动") {
                        state.panBy(0f, PAN_STEP_PX)
                        true
                    },
                ) else emptyList()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val projection = state.projection ?: return@Canvas
            val renderedWidth = bitmap.width * projection.renderedScale
            val renderedHeight = bitmap.height * projection.renderedScale
            val center = Offset(size.width / 2f, size.height / 2f)
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(
                    (center.x + projection.offsetX - renderedWidth / 2f).roundToInt(),
                    (center.y + projection.offsetY - renderedHeight / 2f).roundToInt(),
                ),
                dstSize = IntSize(
                    renderedWidth.roundToInt().coerceAtLeast(1),
                    renderedHeight.roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.High,
            )
        }

        CropMask(
            modifier = Modifier.fillMaxSize(),
            diameterPx = state.viewportDiameterPx,
        )
    }
}

@Composable
private fun CropMask(
    modifier: Modifier,
    diameterPx: Float,
) {
    Canvas(
        modifier = modifier.fillMaxSize().graphicsLayer {
            // The clear blend mode is isolated to the overlay layer so it reveals the image below.
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = diameterPx / 2f
        drawRect(Color.Black.copy(alpha = 0.68f))
        if (radius > 0f) {
            drawCircle(
                color = Color.Transparent,
                radius = radius,
                center = center,
                blendMode = BlendMode.Clear,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.92f),
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun CropZoomControls(
    zoom: Float,
    enabled: Boolean,
    errorMessage: String?,
    onZoomChanged: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        errorMessage?.let {
            Text(
                text = it,
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ZoomIn,
                contentDescription = "缩放",
                tint = Color.White.copy(alpha = 0.84f),
            )
            Slider(
                value = zoom,
                onValueChange = onZoomChanged,
                valueRange = 1f..MAX_PREVIEW_ZOOM,
                steps = 7,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "头像缩放" },
            )
            IconButton(onClick = onReset, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "重置裁剪",
                )
            }
        }
    }
}

/** Compose state that keeps gesture bounds identical to [AvatarCropGeometry]. */
@Stable
private class AvatarCropState(
    private val imageWidth: Int,
    private val imageHeight: Int,
) {
    var zoom by mutableFloatStateOf(1f)
        private set

    private var transform by mutableStateOf(AvatarPreviewTransform())
    private var viewportWidthPx by mutableFloatStateOf(0f)
    private var viewportHeightPx by mutableFloatStateOf(0f)
    var viewportDiameterPx by mutableFloatStateOf(0f)
        private set

    val projection: AvatarCropProjection?
        get() = if (viewportDiameterPx > 0f) {
            AvatarCropGeometry.project(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                viewportDiameter = viewportDiameterPx,
                transform = transform,
            )
        } else {
            null
        }

    fun updateViewport(widthPx: Float, heightPx: Float) {
        if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return
        val diameter = min(widthPx, heightPx) * CROP_DIAMETER_FRACTION
        if (abs(diameter - viewportDiameterPx) < 0.5f) {
            viewportWidthPx = widthPx
            viewportHeightPx = heightPx
            return
        }
        val ratio = if (viewportDiameterPx > 0f) diameter / viewportDiameterPx else 1f
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        viewportDiameterPx = diameter
        transform = transform.copy(
            offsetX = transform.offsetX * ratio,
            offsetY = transform.offsetY * ratio,
        )
        constrainTransform()
    }

    fun applyGesture(centroid: Offset, pan: Offset, zoomChange: Float) {
        if (viewportDiameterPx <= 0f || !zoomChange.isFinite() || zoomChange <= 0f) return
        val oldZoom = zoom
        val nextZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_PREVIEW_ZOOM)
        val ratio = nextZoom / oldZoom
        val focus = Offset(
            x = centroid.x - viewportWidthPx / 2f,
            y = centroid.y - viewportHeightPx / 2f,
        )
        transform = AvatarPreviewTransform(
            zoom = nextZoom,
            offsetX = transform.offsetX * ratio + focus.x * (1f - ratio) + pan.x,
            offsetY = transform.offsetY * ratio + focus.y * (1f - ratio) + pan.y,
        )
        constrainTransform()
    }

    fun updateZoom(value: Float) {
        if (!value.isFinite()) return
        val nextZoom = value.coerceIn(1f, MAX_PREVIEW_ZOOM)
        val ratio = nextZoom / zoom
        zoom = nextZoom
        transform = transform.copy(
            zoom = nextZoom,
            offsetX = transform.offsetX * ratio,
            offsetY = transform.offsetY * ratio,
        )
        constrainTransform()
    }

    fun panBy(deltaX: Float, deltaY: Float) {
        applyGesture(
            centroid = Offset(viewportWidthPx / 2f, viewportHeightPx / 2f),
            pan = Offset(deltaX, deltaY),
            zoomChange = 1f,
        )
    }

    fun reset() {
        zoom = 1f
        transform = AvatarPreviewTransform()
        constrainTransform()
    }

    private fun constrainTransform() {
        val projected = projection ?: return
        zoom = transform.zoom
        transform = transform.copy(
            offsetX = projected.offsetX,
            offsetY = projected.offsetY,
        )
    }
}
