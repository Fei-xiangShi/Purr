package life.fxs.purr.feature.call

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

@Composable
internal fun RealtimeAudioMeter(
    label: String,
    levelPercent: Int,
    speaking: Boolean,
) {
    val normalizedLevel = (animateAudioLevel(levelPercent / 100f) * 100f).coerceIn(0f, 100f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (speaking) "$label · 说话中" else label,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (speaking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${normalizedLevel.toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val inactiveColor = MaterialTheme.colorScheme.outlineVariant
        val normalColor = MaterialTheme.colorScheme.tertiary
        val warningColor = MaterialTheme.colorScheme.secondary
        val peakColor = MaterialTheme.colorScheme.error
        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            val segmentCount = 30
            val gap = 2.dp.toPx()
            val segmentWidth = (size.width - gap * (segmentCount - 1)) / segmentCount
            val activeSegments = ceil(normalizedLevel / 100f * segmentCount).toInt()
            repeat(segmentCount) { index ->
                val threshold = (index + 1) * 100f / segmentCount
                val color = when {
                    index >= activeSegments -> inactiveColor
                    threshold >= 85f -> peakColor
                    threshold >= 65f -> warningColor
                    else -> normalColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * (segmentWidth + gap), 0f),
                    size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun RealtimeLineChart(
    title: String,
    series: List<ChartSeries>,
    valueSuffix: String,
    sampleTimesMillis: List<Long> = emptyList(),
    chartFrameTimeMillis: State<Long>,
    minimumScale: Double = 10.0,
    showDivider: Boolean = true,
) {
    val targetMaxValue = max(
        minimumScale,
        maxVisibleChartValue(
            series = series,
        )?.times(1.15) ?: minimumScale,
    )
    val animatedMaxValue = animateChartScale(targetMaxValue)
    val reusablePaths = remember(series.size) { List(series.size) { Path() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        ChartLegend(series = series, valueSuffix = valueSuffix)
        val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(backgroundColor, RoundedCornerShape(4.dp))
                .padding(6.dp),
        ) {
            val chartTimeCursorMillis = sampleTimesMillis.lastOrNull()?.let { latestSampleTimeMillis ->
                chartTimeCursorMillis(
                    latestSampleTimeMillis = latestSampleTimeMillis,
                    nowMillis = chartFrameTimeMillis.value,
                ).toDouble()
            }
            repeat(5) { index ->
                val y = size.height * index / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            repeat(7) { index ->
                val x = size.width * index / 6f
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            series.forEachIndexed { index, item ->
                drawSeries(
                    path = reusablePaths[index],
                    values = item.values,
                    sampleTimesMillis = sampleTimesMillis,
                    chartTimeCursorMillis = chartTimeCursorMillis,
                    color = item.color,
                    maxValue = animatedMaxValue.value.toDouble(),
                )
            }
        }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ChartLegend(series: List<ChartSeries>, valueSuffix: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(item.color) }
                    Text(
                        text = item.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = item.color,
                    )
                }
                Text(
                    item.values.lastOrNull { it != null }?.let { "${it.formatChartValue()} $valueSuffix" } ?: NO_DATA,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    path: Path,
    values: List<Double?>,
    sampleTimesMillis: List<Long>,
    chartTimeCursorMillis: Double?,
    color: Color,
    maxValue: Double,
) {
    path.reset()
    if (values.isEmpty()) return
    var hasPoint = false
    var previousSampleTimeMillis: Long? = null
    values.forEachIndexed { index, value ->
        val sampleTimeMillis = sampleTimesMillis.getOrNull(index)
        val isVisible = chartTimeCursorMillis == null ||
            sampleTimeMillis == null ||
            isChartSampleVisible(sampleTimeMillis, chartTimeCursorMillis)
        if (value == null || !value.isFinite() || !isVisible) {
            hasPoint = false
            previousSampleTimeMillis = sampleTimeMillis
            return@forEachIndexed
        }
        val x = if (chartTimeCursorMillis != null && sampleTimeMillis != null) {
            val ageMillis = chartTimeCursorMillis - sampleTimeMillis.toDouble()
            size.width * (1.0 - ageMillis / NETWORK_CHART_WINDOW_MILLIS).toFloat()
        } else if (values.size == 1) {
            size.width
        } else {
            size.width * index / (values.size - 1f)
        }
        val y = size.height * (1f - (value / maxValue).coerceIn(0.0, 1.0).toFloat())
        val startsNewSegment = sampleTimeMillis != null &&
            shouldBreakChartPath(previousSampleTimeMillis, sampleTimeMillis)
        if (hasPoint && !startsNewSegment) path.lineTo(x, y) else path.moveTo(x, y)
        hasPoint = true
        previousSampleTimeMillis = sampleTimeMillis
    }
    drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
}

private fun maxVisibleChartValue(
    series: List<ChartSeries>,
): Double? {
    var maximum: Double? = null
    series.forEach { item ->
        item.values.forEachIndexed { index, value ->
            if (value == null || !value.isFinite()) return@forEachIndexed
            maximum = maximum?.let { max(it, value) } ?: value
        }
    }
    return maximum
}

@Immutable
internal data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Double?>,
)

internal const val NO_DATA = "暂无数据"

private fun Double.formatChartValue(): String = if (this >= 100.0) "%.0f".format(this) else "%.1f".format(this)
