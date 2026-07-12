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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.NetworkTransport
import kotlin.math.ceil
import kotlin.math.max

@Composable
internal fun CallDiagnosticsScreen(
    state: CallState,
    callDurationSeconds: Long,
    viewModel: CallDiagnosticsViewModel = hiltViewModel(),
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val networkHistory by viewModel.networkHistory.collectAsStateWithLifecycle()
    CallDiagnosticsContent(
        state = state,
        metrics = metrics,
        networkHistory = networkHistory,
        callDurationSeconds = callDurationSeconds,
    )
}

@Composable
private fun CallDiagnosticsContent(
    state: CallState,
    metrics: CallQualityMetrics,
    networkHistory: List<NetworkGraphSample>,
    callDurationSeconds: Long,
) {
    PurrScreen {
        PurrSectionTitle(
            eyebrow = "通话诊断",
            title = "实时质量",
            subtitle = "",
            modifier = Modifier.fillMaxWidth(),
        )

        PurrPanel(title = "本地状态") {
            DiagnosticMetricRow("网络类型", metrics.device.networkTransport.toDisplayLabel())
            DiagnosticMetricRow("网络验证", if (metrics.device.networkValidated) "已联网" else "未验证")
            DiagnosticMetricRow("计费网络", if (metrics.device.networkMetered) "是" else "否")
            DiagnosticMetricRow("系统通话音量", metrics.device.callVolumePercent.asPercent())
            DiagnosticMetricRow("音频输出", state.activeRoute.toDisplayLabel())
            DiagnosticMetricRow("系统估算上行", metrics.device.estimatedUpstreamKbps.asBitrate())
            DiagnosticMetricRow("系统估算下行", metrics.device.estimatedDownstreamKbps.asBitrate())
        }

        PurrPanel(title = "连接概览") {
            PurrStatusChip(
                label = "通话状态",
                detail = state.screenState.diagnosticsLabel(),
                accentColor = if (state.screenState == CallScreenState.Active) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            DiagnosticMetricRow(
                "通话时长",
                if (state.screenState == CallScreenState.Active || callDurationSeconds > 0L) {
                    callDurationSeconds.toCallDuration()
                } else {
                    "未开始"
                },
            )
            DiagnosticMetricRow("通话质量", state.session.networkQualityLabel())
            DiagnosticMetricRow("远端参与者", if (metrics.remoteConnected) "已连接" else "未连接")
        }

        PurrPanel(title = "实时音量") {
            RealtimeAudioMeter(
                label = "本地麦克风",
                levelPercent = metrics.audio.localLevelPercent,
                speaking = metrics.audio.localSpeaking,
            )
            RealtimeAudioMeter(
                label = "远端声音",
                levelPercent = metrics.audio.remoteLevelPercent,
                speaking = metrics.audio.remoteSpeaking,
            )
            DiagnosticMetricRow("发送编码", metrics.audio.sendCodec ?: NO_DATA)
            DiagnosticMetricRow("接收编码", metrics.audio.receiveCodec ?: NO_DATA)
        }

        PurrPanel(title = "网络趋势") {
            RealtimeLineChart(
                title = "延迟与抖动",
                series = listOf(
                    ChartSeries(
                        label = "RTT",
                        color = MaterialTheme.colorScheme.tertiary,
                        values = networkHistory.map { it.roundTripTimeMs },
                    ),
                    ChartSeries(
                        label = "抖动",
                        color = MaterialTheme.colorScheme.secondary,
                        values = networkHistory.map { it.jitterMs },
                    ),
                ),
                valueSuffix = "ms",
            )
            RealtimeLineChart(
                title = "丢包",
                series = listOf(
                    ChartSeries(
                        label = "丢包率",
                        color = MaterialTheme.colorScheme.error,
                        values = networkHistory.map { it.packetLossPercent },
                    ),
                ),
                valueSuffix = "%",
                minimumScale = 1.0,
            )
            RealtimeLineChart(
                title = "实际吞吐",
                series = listOf(
                    ChartSeries(
                        label = "上行",
                        color = MaterialTheme.colorScheme.primary,
                        values = networkHistory.map { it.uplinkBitrateKbps.toMbpsOrNull() },
                    ),
                    ChartSeries(
                        label = "下行",
                        color = MaterialTheme.colorScheme.tertiary,
                        values = networkHistory.map { it.downlinkBitrateKbps.toMbpsOrNull() },
                    ),
                ),
                valueSuffix = "Mbps",
            )
            RealtimeLineChart(
                title = "本地链路估算",
                series = listOf(
                    ChartSeries(
                        label = "估算上行",
                        color = MaterialTheme.colorScheme.primary,
                        values = networkHistory.map { it.estimatedUpstreamKbps.toMbpsOrNull() },
                    ),
                    ChartSeries(
                        label = "估算下行",
                        color = MaterialTheme.colorScheme.secondary,
                        values = networkHistory.map { it.estimatedDownstreamKbps.toMbpsOrNull() },
                    ),
                ),
                valueSuffix = "Mbps",
            )
        }

        PurrPanel(title = "WebRTC 实时统计") {
            DiagnosticMetricRow("往返延迟", metrics.transport.roundTripTimeMs.asMillis())
            DiagnosticMetricRow("上行实际码率", metrics.transport.uplinkBitrateKbps.asBitrate())
            DiagnosticMetricRow("下行实际码率", metrics.transport.downlinkBitrateKbps.asBitrate())
            DiagnosticMetricRow("上行丢包", metrics.transport.uplinkPacketLossPercent.asPercent())
            DiagnosticMetricRow("下行丢包", metrics.transport.downlinkPacketLossPercent.asPercent())
            DiagnosticMetricRow("接收抖动", metrics.transport.jitterMs.asMillis())
            DiagnosticMetricRow("可用上行带宽", metrics.transport.availableOutgoingKbps.asBitrate())
            DiagnosticMetricRow("可用下行带宽", metrics.transport.availableIncomingKbps.asBitrate())
            DiagnosticMetricRow("传输路径", metrics.transport.path ?: NO_DATA)
        }

    }
}

@Composable
private fun RealtimeAudioMeter(
    label: String,
    levelPercent: Int,
    speaking: Boolean,
) {
    val normalizedLevel = levelPercent.coerceIn(0, 100)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (speaking) "$label · 说话中" else label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (speaking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = normalizedLevel.asPercent(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val inactiveColor = MaterialTheme.colorScheme.outlineVariant
        val normalColor = MaterialTheme.colorScheme.tertiary
        val warningColor = MaterialTheme.colorScheme.secondary
        val peakColor = MaterialTheme.colorScheme.error
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
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
private fun RealtimeLineChart(
    title: String,
    series: List<ChartSeries>,
    valueSuffix: String,
    minimumScale: Double = 10.0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
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
            repeat(5) { index ->
                val y = size.height * index / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            repeat(7) { index ->
                val x = size.width * index / 6f
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            val maxValue = max(
                minimumScale,
                series.flatMap { it.values }.mapNotNull { it }.maxOrNull()?.times(1.15) ?: minimumScale,
            )
            series.forEach { item ->
                drawSeries(
                    values = item.values,
                    color = item.color,
                    maxValue = maxValue,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ChartLegend(
    series: List<ChartSeries>,
    valueSuffix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(item.color) }
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = item.color,
                    )
                }
                Text(
                    text = item.values.lastOrNull { it != null }
                        ?.let { "${it.formatChartValue()} $valueSuffix" }
                        ?: NO_DATA,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    values: List<Double?>,
    color: Color,
    maxValue: Double,
) {
    if (values.isEmpty()) return
    val path = Path()
    var hasPoint = false
    values.forEachIndexed { index, value ->
        if (value == null) {
            hasPoint = false
            return@forEachIndexed
        }
        val x = if (values.size == 1) size.width else size.width * index / (values.size - 1f)
        val y = size.height * (1f - (value / maxValue).coerceIn(0.0, 1.0).toFloat())
        if (hasPoint) path.lineTo(x, y) else path.moveTo(x, y)
        hasPoint = true
    }
    drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
}

private data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Double?>,
)

@Composable
private fun DiagnosticMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun CallSession?.networkQualityLabel(): String = when (this?.uiSnapshot?.networkQuality?.uplinkScore) {
    5 -> "极佳"
    4 -> "良好"
    3 -> "一般"
    2 -> "较差"
    1 -> "连接中断"
    else -> NO_DATA
}

private fun CallScreenState.diagnosticsLabel(): String = when (this) {
    CallScreenState.Idle -> "空闲"
    CallScreenState.Dialing -> "呼叫中"
    CallScreenState.Connecting -> "连接中"
    CallScreenState.Waiting -> "等待对方"
    CallScreenState.Active -> "通话中"
    CallScreenState.Reconnecting -> "重连中"
    CallScreenState.Ending -> "结束中"
    CallScreenState.Ended -> "已结束"
}

private fun NetworkTransport.toDisplayLabel(): String = when (this) {
    NetworkTransport.Wifi -> "Wi-Fi"
    NetworkTransport.Cellular -> "移动网络"
    NetworkTransport.Ethernet -> "以太网"
    NetworkTransport.Vpn -> "VPN"
    NetworkTransport.Bluetooth -> "蓝牙网络"
    NetworkTransport.Other -> "其他"
    NetworkTransport.None -> "无可用网络"
}

private fun Int.asPercent(): String = "$this%"

private fun Double?.asPercent(): String = this?.let { "%.2f%%".format(it) } ?: NO_DATA

private fun Double?.asMillis(): String = this?.let { "%.1f ms".format(it) } ?: NO_DATA

private fun Double?.asBitrate(): String = this?.let { "%.2f Mbps".format(it / 1_000.0) } ?: NO_DATA

private fun Double?.toMbpsOrNull(): Double? = this?.div(1_000.0)

private fun Double.formatChartValue(): String = if (this >= 100.0) "%.0f".format(this) else "%.1f".format(this)

private const val NO_DATA = "暂无数据"
