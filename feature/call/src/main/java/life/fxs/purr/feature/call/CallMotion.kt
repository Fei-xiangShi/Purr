package life.fxs.purr.feature.call

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/** Shared display timing. Compose drives these transitions from its frame clock. */
internal const val CALL_UI_FRAME_INTERVAL_MILLIS = 16
internal const val AUDIO_LEVEL_TRANSITION_MILLIS = CALL_UI_FRAME_INTERVAL_MILLIS * 3
internal const val CHART_TRANSITION_MILLIS = CALL_UI_FRAME_INTERVAL_MILLIS * 3
internal const val NETWORK_CHART_SAMPLE_CAPACITY = 150
internal const val NETWORK_CHART_WINDOW_MILLIS = 7_500L
internal const val NETWORK_CHART_MAX_CONTIGUOUS_GAP_MILLIS = 150L

@Composable
internal fun animateAudioLevel(level: Float): Float {
    val normalizedLevel = level.coerceIn(0f, 1f)
    return animateFloatAsState(
        targetValue = normalizedLevel,
        animationSpec = tween(
            durationMillis = AUDIO_LEVEL_TRANSITION_MILLIS,
            easing = LinearEasing,
        ),
        label = "audio-level",
    ).value
}

@Composable
internal fun animateChartScale(maxValue: Double): State<Float> = animateFloatAsState(
    targetValue = maxValue.toFloat(),
    animationSpec = tween(
        durationMillis = CHART_TRANSITION_MILLIS,
        easing = LinearEasing,
    ),
    label = "chart-scale",
)

@Composable
internal fun rememberChartFrameTimeMillis(active: Boolean): State<Long> {
    val frameTimeMillis = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                frameTimeMillis.longValue = SystemClock.elapsedRealtime()
            }
        }
    }
    return frameTimeMillis
}

internal fun chartTimeCursorMillis(latestSampleTimeMillis: Long, nowMillis: Long): Long =
    maxOf(latestSampleTimeMillis, nowMillis)

internal fun isChartSampleVisible(sampleTimeMillis: Long, chartTimeCursorMillis: Double): Boolean {
    val ageMillis = chartTimeCursorMillis - sampleTimeMillis.toDouble()
    return ageMillis >= 0.0 && ageMillis <= NETWORK_CHART_WINDOW_MILLIS.toDouble()
}

internal fun shouldBreakChartPath(previousTimeMillis: Long?, currentTimeMillis: Long): Boolean {
    previousTimeMillis ?: return false
    val gapMillis = currentTimeMillis - previousTimeMillis
    return gapMillis <= 0L || gapMillis > NETWORK_CHART_MAX_CONTIGUOUS_GAP_MILLIS
}
