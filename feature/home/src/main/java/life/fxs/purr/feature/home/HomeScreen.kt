package life.fxs.purr.feature.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrAvatar
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.core.model.CallSessionSummary

@Composable
fun HomeScreenRoute(
    onOpenCall: (String) -> Unit,
    onOpenCallHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateToCall -> onOpenCall(effect.pairId)
                is HomeEffect.ShowError -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    HomeScreen(
        state = state,
        onStartCall = { viewModel.onIntent(HomeIntent.StartCall) },
        onRefreshStatus = { viewModel.onIntent(HomeIntent.RefreshStatus) },
        onAcceptIncomingCall = { viewModel.onIntent(HomeIntent.AcceptIncomingCall) },
        onDeclineIncomingCall = { viewModel.onIntent(HomeIntent.DeclineIncomingCall) },
        onOpenCallHistory = onOpenCallHistory,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun HomeScreen(
    state: HomeState,
    onStartCall: () -> Unit,
    onRefreshStatus: () -> Unit,
    onAcceptIncomingCall: () -> Unit,
    onDeclineIncomingCall: () -> Unit,
    onOpenCallHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val selfName = state.self?.displayName ?: "用户"
    val partnerName = state.partner?.displayName ?: "暂无配对对象"

    PurrScreen {
        PurrSectionTitle(
            eyebrow = "首页",
            title = "你好，$selfName",
            subtitle = "",
            modifier = Modifier,
        )

        state.incomingCall?.let {
            PurrPanel(
                title = "$partnerName 正在呼叫",
                subtitle = "",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                PurrPrimaryButton(
                    text = "接听",
                    onClick = onAcceptIncomingCall,
                )
                PurrSecondaryButton(
                    text = "拒绝",
                    onClick = onDeclineIncomingCall,
                )
            }
        }

        PurrPanel(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PurrAvatar(
                    avatarUrl = state.partner?.avatarUrl,
                    contentDescription = "对方头像",
                    size = 64.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = partnerName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            PurrStatusChip(
                label = if (state.partner?.isOnline == true) "对方在线" else "对方离线",
                detail = if (state.isCallable) "可通话" else "暂不可通话",
                accentColor = if (state.partner?.isOnline == true) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            if (state.isLoading) {
                PurrStatusChip(
                    label = "刷新中",
                    detail = "",
                    accentColor = MaterialTheme.colorScheme.primary,
                )
            }
        }

        PurrPanel(title = "操作") {
            PurrPrimaryButton(
                text = when {
                    state.hasActiveCall -> "回到通话"
                    state.isCallable -> "发起通话"
                    else -> "暂不可通话"
                },
                onClick = onStartCall,
                enabled = state.hasActiveCall || (!state.isLoading && state.isCallable),
            )
            PurrSecondaryButton(
                text = if (state.isLoading) "刷新中..." else "刷新状态",
                onClick = onRefreshStatus,
                enabled = !state.isLoading,
            )
            PurrSecondaryButton(
                text = "通话历史",
                onClick = onOpenCallHistory,
            )
            PurrSecondaryButton(
                text = "设置",
                onClick = onOpenSettings,
            )
        }

        state.lastSessionSummary?.let { summary ->
            LastSessionPanel(summary = summary)
        }
    }
}

@Composable
private fun LastSessionPanel(summary: CallSessionSummary) {
    PurrPanel(title = "最近一次通话") {
        PurrStatusChip(
            label = "时长",
            detail = summary.durationSeconds?.toReadableDuration() ?: "同步中",
            accentColor = MaterialTheme.colorScheme.primary,
        )
        PurrStatusChip(
            label = "录音",
            detail = if (summary.recordingActive) "已开启" else "未开启",
            accentColor = if (summary.recordingActive) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
        Text(
            text = "通话 #${summary.callId}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Long.toReadableDuration(): String {
    val minutes = this / 60
    val seconds = this % 60
    return if (minutes > 0) {
        "${minutes}分${seconds}秒"
    } else {
        "${seconds}秒"
    }
}
