package life.fxs.purr.feature.call

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.domain.call.model.CallDetail
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallOutcome
import life.fxs.purr.domain.call.model.CallQualitySummary
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallRecordingStatus
import life.fxs.purr.domain.call.model.CallTranscriptStatus

@Composable
fun CallDetailScreenRoute(
    onBack: () -> Unit,
    onDownload: (RecordingDownloadRequest) -> Unit,
    viewModel: CallDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, onDownload) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CallDetailEffect.DownloadReady -> onDownload(
                    RecordingDownloadRequest(effect.url, effect.fileName),
                )
            }
        }
    }
    CallDetailScreen(
        state = state,
        onBack = onBack,
        onIntent = viewModel::onIntent,
    )
}

@Composable
fun CallDetailScreen(
    state: CallDetailState,
    onBack: () -> Unit,
    onIntent: (CallDetailIntent) -> Unit,
) {
    PurrScreen {
        ScreenBackHeader(title = "通话详情", subtitle = "#${state.callId}", onBack = onBack)

        AnimatedContent(
            targetState = state.detail,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
            label = "call-detail-content",
        ) { detail ->
            if (detail == null) {
                if (state.isLoading) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                DetailOverview(detail)
            }
        }

        state.errorMessage?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(CallDetailIntent.Retry) }) { Text("重试") }
            }
        }

        if (state.detail != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            DetailSectionTitle(Icons.Filled.NetworkCheck, "通话质量")
            QualitySection(state.detail.quality)

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            DetailSectionTitle(Icons.Filled.Mic, "通话录音")
            RecordingSection(
                recordings = state.recordings,
                downloadingIds = state.downloadingRecordingIds,
                onDownload = { onIntent(CallDetailIntent.DownloadRecording(it)) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            DetailSectionTitle(Icons.Filled.Description, "转录文字")
            val transcript = state.transcript
            Text(
                text = when (transcript?.status) {
                    CallTranscriptStatus.Available -> transcript.text.orEmpty()
                    CallTranscriptStatus.Processing -> "转录处理中"
                    CallTranscriptStatus.Failed -> "转录失败"
                    CallTranscriptStatus.Unavailable, null -> "暂未提供转录文字"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("查看转录")
            }
        }
    }
}

@Composable
private fun DetailOverview(detail: CallDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val icon = detail.icon()
            Surface(shape = CircleShape, color = detail.iconContainerColor()) {
                Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(detail.title(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    detail.requestedAtEpochMillis.toHistoryDate(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        InfoRow("发起时间", detail.requestedAtEpochMillis.toHistoryDate())
        InfoRow("接通时间", detail.connectedAtEpochMillis?.toHistoryDate() ?: "未接通")
        InfoRow("结束时间", detail.endedAtEpochMillis.toHistoryDate())
        InfoRow("响铃时长", detail.ringingDurationMillis.toHistoryDuration())
        InfoRow("通话时长", detail.durationMillis.toHistoryDuration())
        InfoRow("录音状态", detail.recordingStatus.toRecordingLabel())
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailSectionTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun QualitySection(quality: CallQualitySummary?) {
    if (quality == null) {
        Text("本次通话暂无质量样本", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoRow("采样数量", "${quality.sampleCount} 个")
        InfoRow("平均往返延迟", quality.averageRoundTripTimeMs.toMetric("ms"))
        InfoRow("平均抖动", quality.averageJitterMs.toMetric("ms"))
        InfoRow("平均丢包", quality.averagePacketLossPercent.toMetric("%"))
        InfoRow("峰值丢包", quality.maximumPacketLossPercent.toMetric("%"))
        InfoRow("平均上行", quality.averageUplinkBitrateKbps.toMetric("kbps"))
        InfoRow("平均下行", quality.averageDownlinkBitrateKbps.toMetric("kbps"))
        if (quality.networkTransports.isNotEmpty()) InfoRow("网络类型", quality.networkTransports.joinToString())
        if (quality.codecs.isNotEmpty()) InfoRow("音频编码", quality.codecs.joinToString())
    }
}

@Composable
private fun RecordingSection(
    recordings: List<CallRecording>,
    downloadingIds: Set<String>,
    onDownload: (String) -> Unit,
) {
    if (recordings.isEmpty()) {
        Text("本次通话没有录音", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        recordings.forEachIndexed { index, recording ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("录音 ${index + 1}", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                recording.durationMillis?.toHistoryDuration(),
                                recording.sizeBytes?.toReadableSize(),
                                recording.status.label(),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (recording.downloadAvailable) {
                        IconButton(
                            onClick = { onDownload(recording.recordingId) },
                            enabled = recording.recordingId !in downloadingIds,
                        ) {
                            if (recording.recordingId in downloadingIds) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Download, contentDescription = "下载录音")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun CallDetail.icon(): ImageVector = when {
    outcome == CallOutcome.Missed -> Icons.AutoMirrored.Filled.PhoneMissed
    direction == CallDirection.Incoming -> Icons.AutoMirrored.Filled.CallReceived
    else -> Icons.AutoMirrored.Filled.CallMade
}

@Composable
private fun CallDetail.iconContainerColor() = when (outcome) {
    CallOutcome.Missed -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.primaryContainer
}

private fun CallDetail.title(): String = when (outcome) {
    CallOutcome.Missed -> "未接来电"
    CallOutcome.Cancelled -> "已取消呼叫"
    CallOutcome.Completed -> if (direction == CallDirection.Incoming) "呼入通话" else "呼出通话"
}

private fun Double?.toMetric(unit: String): String =
    this?.let { String.format(Locale.US, "%.1f %s", it, unit) } ?: "--"

private fun Long.toReadableSize(): String = when {
    this >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", this / 1024.0 / 1024.0)
    this >= 1024L -> String.format(Locale.US, "%.1f KB", this / 1024.0)
    else -> "$this B"
}

private fun CallRecordingStatus.label(): String = when (this) {
    CallRecordingStatus.Processing -> "处理中"
    CallRecordingStatus.Available -> "可下载"
    CallRecordingStatus.Expired -> "已过期"
    CallRecordingStatus.Failed -> "处理失败"
}

private fun String.toRecordingLabel(): String = when (lowercase()) {
    "stopped" -> "可下载"
    "recording" -> "录制中"
    "failed" -> "录制失败"
    "deleted" -> "已过期"
    else -> "无录音"
}
