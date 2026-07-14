@file:Suppress("DEPRECATION")

package life.fxs.purr.core.designsystem.component

import android.graphics.Bitmap
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation

/**
 * Presentation-only shell shared by incoming, outgoing and active call surfaces.
 * Call state and actions deliberately stay in their owning feature modules.
 */
@Composable
fun VoiceCallScaffold(
    partnerName: String,
    partnerAvatarUrl: String?,
    status: String,
    detail: String = "",
    avatarModifier: Modifier = Modifier,
    remoteAudioLevel: Float = 0f,
    modifier: Modifier = Modifier,
    bottomContent: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        VoiceCallBackdrop(avatarUrl = partnerAvatarUrl)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(0.72f))
            VoiceActivityAvatar(
                avatarUrl = partnerAvatarUrl,
                contentDescription = "$partnerName 的头像",
                audioLevel = remoteAudioLevel,
                avatarModifier = avatarModifier,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = partnerName,
                modifier = Modifier.widthIn(max = 320.dp),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = bottomContent,
            )
        }
    }
}

@Composable
private fun VoiceCallBackdrop(avatarUrl: String?) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xFF17131B))) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .size(384)
                    .transformations(LegacyGaussianBlurTransformation(context.applicationContext))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.22f
                        scaleY = 1.22f
                        alpha = 0.74f
                    }
                    .blur(54.dp),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.28f),
                        0.48f to Color(0xFF211829).copy(alpha = 0.56f),
                        1f to Color.Black.copy(alpha = 0.74f),
                    ),
                ),
        )
    }
}

/** Compose uses RenderEffect on API 31+; this cached Coil transform keeps API 29-30 equivalent. */
private class LegacyGaussianBlurTransformation(
    private val context: android.content.Context,
) : Transformation {
    override val cacheKey: String = "purr-legacy-gaussian-blur-v1"

    @Suppress("DEPRECATION")
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return input
        val output = input.copy(Bitmap.Config.ARGB_8888, true) ?: return input
        val renderScript = runCatching { RenderScript.create(context) }.getOrNull() ?: return input
        var source: Allocation? = null
        var destination: Allocation? = null
        var blur: ScriptIntrinsicBlur? = null
        return try {
            source = Allocation.createFromBitmap(renderScript, output)
            destination = Allocation.createTyped(renderScript, source.type)
            blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript)).apply {
                setRadius(25f)
                setInput(source)
                forEach(destination)
            }
            destination.copyTo(output)
            output
        } catch (_: RuntimeException) {
            input
        } finally {
            blur?.destroy()
            destination?.destroy()
            source?.destroy()
            renderScript.destroy()
        }
    }
}

@Composable
private fun VoiceActivityAvatar(
    avatarUrl: String?,
    contentDescription: String,
    audioLevel: Float,
    avatarModifier: Modifier,
) {
    val normalizedTarget = audioLevel.coerceIn(0f, 1f)
    val normalizedLevel by animateFloatAsState(
        targetValue = normalizedTarget,
        animationSpec = tween(durationMillis = 48, easing = LinearEasing),
        label = "remote-audio-level",
    )
    val transition = rememberInfiniteTransition(label = "voice-ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice-ripple-phase",
    )
    Box(
        modifier = Modifier.size(226.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (normalizedLevel <= 0.012f) return@Canvas
            val baseRadius = 78.dp.toPx()
            repeat(3) { index ->
                val progress = (phase + index / 3f) % 1f
                val levelExpansion = 28.dp.toPx() * normalizedLevel
                drawCircle(
                    color = Color.White.copy(
                        alpha = (1f - progress) * (0.10f + normalizedLevel * 0.24f),
                    ),
                    radius = baseRadius + levelExpansion + progress * 30.dp.toPx(),
                )
            }
        }
        PurrAvatar(
            avatarUrl = avatarUrl,
            contentDescription = contentDescription,
            modifier = avatarModifier,
            size = 156.dp,
        )
    }
}
