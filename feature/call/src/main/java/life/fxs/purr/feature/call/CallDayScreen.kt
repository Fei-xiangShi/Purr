package life.fxs.purr.feature.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallHistoryEntry
import life.fxs.purr.domain.call.model.CallOutcome

@Composable
fun CallDayScreenRoute(
    onBack: () -> Unit,
    onOpenCall: (String) -> Unit,
    viewModel: CallDayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallDayScreen(
        state = state,
        onBack = onBack,
        onOpenCall = onOpenCall,
        onIntent = viewModel::onIntent,
    )
}

@Composable
fun CallDayScreen(
    state: CallDayState,
    onBack: () -> Unit,
    onOpenCall: (String) -> Unit,
    onIntent: (CallDayIntent) -> Unit,
) {
    PurrScreen {
        ScreenBackHeader(
            title = state.date.toChineseDate(),
            subtitle = "${state.calls.size} 次通话",
            onBack = onBack,
        )

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        state.errorMessage?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(CallDayIntent.Retry) }) { Text("重试") }
            }
        }

        if (!state.isLoading && state.calls.isEmpty() && state.errorMessage == null) {
            Text("当天没有通话记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.calls.forEachIndexed { index, call ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(220, delayMillis = (index.coerceAtMost(6) * 35))) +
                        slideInVertically(tween(260, delayMillis = (index.coerceAtMost(6) * 35))) { it / 4 },
                ) {
                    CallHistoryRow(call = call, onClick = { onOpenCall(call.callId) })
                }
            }
        }

        if (state.nextCursor != null) {
            TextButton(
                onClick = { onIntent(CallDayIntent.LoadMore) },
                enabled = !state.isLoadingMore,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                if (state.isLoadingMore) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("加载更多")
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(call: CallHistoryEntry, onClick: () -> Unit) {
    val icon = call.presentationIcon()
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = call.iconContainerColor(),
                contentColor = call.iconContentColor(),
            ) {
                Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = call.presentationTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(call.startedAtEpochMillis.toCallTime(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", color = MaterialTheme.colorScheme.outline)
                    Text(call.durationMillis.toHistoryDuration(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (call.recordingStatus.equals("stopped", true)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("有录音", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "查看详情")
        }
    }
}

@Composable
internal fun ScreenBackHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun CallHistoryEntry.presentationIcon(): ImageVector = when {
    outcome == CallOutcome.Missed -> Icons.AutoMirrored.Filled.PhoneMissed
    direction == CallDirection.Incoming -> Icons.AutoMirrored.Filled.CallReceived
    else -> Icons.AutoMirrored.Filled.CallMade
}

@Composable
private fun CallHistoryEntry.iconContainerColor() =
    if (outcome == CallOutcome.Missed) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer

@Composable
private fun CallHistoryEntry.iconContentColor() =
    if (outcome == CallOutcome.Missed) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer

internal fun CallHistoryEntry.presentationTitle(): String = when (outcome) {
    CallOutcome.Missed -> "未接来电"
    CallOutcome.Cancelled -> "已取消呼叫"
    CallOutcome.Completed -> if (direction == CallDirection.Incoming) "呼入通话" else "呼出通话"
}

internal fun LocalDate.toChineseDate(): String =
    DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE).format(this)

internal fun Long.toCallTime(zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)
        .format(Instant.ofEpochMilli(this).atZone(zoneId))

internal fun Long.toHistoryDate(zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
        .format(Instant.ofEpochMilli(this).atZone(zoneId))

internal fun Long.toHistoryDuration(): String {
    val totalSeconds = (this / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}小时${minutes}分${seconds}秒"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}
