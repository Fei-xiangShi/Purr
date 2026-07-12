package life.fxs.purr.feature.call

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton
import life.fxs.purr.core.designsystem.component.PurrStatusChip

@Composable
fun CallHistoryScreenRoute(
    viewModel: CallHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallHistoryScreen(
        state = state,
        onRefresh = { viewModel.onIntent(CallHistoryIntent.Refresh) },
        onLoadMore = { viewModel.onIntent(CallHistoryIntent.LoadMore) },
    )
}

@Composable
fun CallHistoryScreen(
    state: CallHistoryState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
) {
    PurrScreen {
        PurrSectionTitle(eyebrow = "通话", title = "通话历史", subtitle = "")
        PurrPanel(title = "历史记录") {
            PurrSecondaryButton(
                text = if (state.isLoading) "加载中..." else "刷新",
                onClick = onRefresh,
                enabled = !state.isLoading && !state.isLoadingMore,
            )
            state.errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (!state.isLoading && state.calls.isEmpty()) {
                Text("暂无通话记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.calls.forEach { call ->
                PurrStatusChip(
                    label = call.startedAtEpochMillis.toHistoryDate(),
                    detail = call.durationMillis.toHistoryDuration(),
                    accentColor = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.nextCursor != null) {
                PurrSecondaryButton(
                    text = if (state.isLoadingMore) "加载中..." else "加载更多",
                    onClick = onLoadMore,
                    enabled = !state.isLoading && !state.isLoadingMore,
                )
            }
        }
    }
}

internal fun Long.toHistoryDate(zoneId: ZoneId = ZoneId.systemDefault()): String =
    HISTORY_DATE_FORMATTER.format(Instant.ofEpochMilli(this).atZone(zoneId))

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

private val HISTORY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
