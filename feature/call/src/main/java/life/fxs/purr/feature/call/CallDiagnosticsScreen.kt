package life.fxs.purr.feature.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.model.NetworkTransport

@Composable
internal fun CallDiagnosticsScreen(
    context: CallDiagnosticsContext,
    callDurationSeconds: Long,
    viewModel: CallDiagnosticsViewModel = hiltViewModel(),
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val networkHistory by viewModel.networkHistory.collectAsStateWithLifecycle()
    CallDiagnosticsContent(
        context = context,
        metrics = metrics,
        networkHistory = networkHistory,
        callDurationSeconds = callDurationSeconds,
    )
}

@Composable
private fun CallDiagnosticsContent(
    context: CallDiagnosticsContext,
    metrics: CallQualityMetrics,
    networkHistory: List<NetworkGraphSample>,
    callDurationSeconds: Long,
) {
    val networkCharts = rememberNetworkChartModel(networkHistory)
    val chartFrameTimeMillis = rememberChartFrameTimeMillis(
        active = networkCharts.sampleTimesMillis.isNotEmpty(),
    )
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
            DiagnosticMetricRow("音频输出", context.activeRoute.displayLabel)
            DiagnosticMetricRow("系统估算上行", metrics.device.estimatedUpstreamKbps.asBitrate())
            DiagnosticMetricRow(
                "系统估算下行",
                metrics.device.estimatedDownstreamKbps.asBitrate(),
                showDivider = false,
            )
        }

        PurrPanel(title = "连接概览") {
            PurrStatusChip(
                label = "通话状态",
                detail = context.screenState.diagnosticsLabel(),
                accentColor = if (context.screenState == CallScreenState.Active) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            DiagnosticMetricRow(
                "通话时长",
                if (context.screenState == CallScreenState.Active || callDurationSeconds > 0L) {
                    callDurationSeconds.toCallDuration()
                } else {
                    "未开始"
                },
            )
            DiagnosticMetricRow("通话质量", context.networkQualityScore.networkQualityLabel())
            DiagnosticMetricRow(
                "远端参与者",
                if (metrics.remoteConnected) "已连接" else "未连接",
                showDivider = false,
            )
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
            DiagnosticMetricRow(
                "接收编码",
                metrics.audio.receiveCodec ?: NO_DATA,
                showDivider = false,
            )
        }

        NetworkTrendPanel(
            model = networkCharts,
            chartFrameTimeMillis = chartFrameTimeMillis,
        )

        PurrPanel(title = "WebRTC 实时统计") {
            DiagnosticMetricRow("往返延迟", metrics.transport.roundTripTimeMs.asMillis())
            DiagnosticMetricRow("上行实际码率", metrics.transport.uplinkBitrateKbps.asBitrate())
            DiagnosticMetricRow("下行实际码率", metrics.transport.downlinkBitrateKbps.asBitrate())
            DiagnosticMetricRow("上行丢包", metrics.transport.uplinkPacketLossPercent.asPercent())
            DiagnosticMetricRow("下行丢包", metrics.transport.downlinkPacketLossPercent.asPercent())
            DiagnosticMetricRow("接收抖动", metrics.transport.jitterMs.asMillis())
            DiagnosticMetricRow("可用上行带宽", metrics.transport.availableOutgoingKbps.asBitrate())
            DiagnosticMetricRow("可用下行带宽", metrics.transport.availableIncomingKbps.asBitrate())
            DiagnosticMetricRow(
                "传输路径",
                metrics.transport.path ?: NO_DATA,
                showDivider = false,
            )
        }

    }
}

@Composable
private fun NetworkTrendPanel(
    model: NetworkChartModel,
    chartFrameTimeMillis: State<Long>,
) {
    PurrPanel(title = "网络趋势") {
        RealtimeLineChart(
            title = "延迟与抖动",
            sampleTimesMillis = model.sampleTimesMillis,
            chartFrameTimeMillis = chartFrameTimeMillis,
            series = model.latencySeries,
            valueSuffix = "ms",
        )
        RealtimeLineChart(
            title = "丢包",
            sampleTimesMillis = model.sampleTimesMillis,
            chartFrameTimeMillis = chartFrameTimeMillis,
            series = model.packetLossSeries,
            valueSuffix = "%",
            minimumScale = 1.0,
        )
        RealtimeLineChart(
            title = "实际吞吐",
            sampleTimesMillis = model.sampleTimesMillis,
            chartFrameTimeMillis = chartFrameTimeMillis,
            series = model.throughputSeries,
            valueSuffix = "Mbps",
        )
        RealtimeLineChart(
            title = "本地链路估算",
            sampleTimesMillis = model.sampleTimesMillis,
            chartFrameTimeMillis = chartFrameTimeMillis,
            series = model.linkEstimateSeries,
            valueSuffix = "Mbps",
            showDivider = false,
        )
    }
}

@Composable
private fun rememberNetworkChartModel(history: List<NetworkGraphSample>): NetworkChartModel {
    val colors = MaterialTheme.colorScheme
    return remember(history, colors) {
        NetworkChartModel(
            sampleTimesMillis = history.map { it.sampledAtMillis },
            latencySeries = listOf(
                ChartSeries(
                    label = "RTT",
                    color = colors.tertiary,
                    values = history.map { it.roundTripTimeMs },
                ),
                ChartSeries(
                    label = "抖动",
                    color = colors.secondary,
                    values = history.map { it.jitterMs },
                ),
            ),
            packetLossSeries = listOf(
                ChartSeries(
                    label = "丢包率",
                    color = colors.error,
                    values = history.map { it.packetLossPercent },
                ),
            ),
            throughputSeries = listOf(
                ChartSeries(
                    label = "上行",
                    color = colors.primary,
                    values = history.map { it.uplinkBitrateKbps.toMbpsOrNull() },
                ),
                ChartSeries(
                    label = "下行",
                    color = colors.tertiary,
                    values = history.map { it.downlinkBitrateKbps.toMbpsOrNull() },
                ),
            ),
            linkEstimateSeries = listOf(
                ChartSeries(
                    label = "估算上行",
                    color = colors.primary,
                    values = history.map { it.estimatedUpstreamKbps.toMbpsOrNull() },
                ),
                ChartSeries(
                    label = "估算下行",
                    color = colors.secondary,
                    values = history.map { it.estimatedDownstreamKbps.toMbpsOrNull() },
                ),
            ),
        )
    }
}

@Immutable
private data class NetworkChartModel(
    val sampleTimesMillis: List<Long>,
    val latencySeries: List<ChartSeries>,
    val packetLossSeries: List<ChartSeries>,
    val throughputSeries: List<ChartSeries>,
    val linkEstimateSeries: List<ChartSeries>,
)

@Composable
private fun DiagnosticMetricRow(
    label: String,
    value: String,
    showDivider: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun Int?.networkQualityLabel(): String = when (this) {
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
    CallScreenState.SystemCallSuspended -> "系统电话挂起"
    CallScreenState.ResumingAfterSystemCall -> "系统电话恢复中"
    CallScreenState.RemoteSystemCallSuspended -> "对方系统电话中"
    CallScreenState.RemoteResumingAfterSystemCall -> "对方恢复中"
    CallScreenState.Reconnecting -> "重连中"
    CallScreenState.Ending -> "结束中"
    CallScreenState.Ended -> "已结束"
    CallScreenState.Failed -> "失败"
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
