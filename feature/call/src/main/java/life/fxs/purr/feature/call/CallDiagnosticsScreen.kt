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
import androidx.compose.runtime.getValue
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
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.NetworkTransport

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
            DiagnosticMetricRow(
                "系统估算下行",
                metrics.device.estimatedDownstreamKbps.asBitrate(),
                showDivider = false,
            )
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
                showDivider = false,
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
            DiagnosticMetricRow(
                "传输路径",
                metrics.transport.path ?: NO_DATA,
                showDivider = false,
            )
        }

    }
}

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
