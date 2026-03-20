package life.fxs.purr.feature.settings

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton
import life.fxs.purr.core.designsystem.component.PurrStatusChip

@Composable
fun SettingsScreenRoute(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.LoggedOut -> onLoggedOut()
                is SettingsEffect.ShowMessage -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsScreen(
        state = state,
        onBack = onBack,
        onLogout = viewModel::logout,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    PurrScreen {
        PurrSectionTitle(
            eyebrow = "设置",
            title = "账户设置",
            subtitle = "",
            modifier = Modifier,
        )

        PurrPanel(
            title = state.self?.displayName ?: "未登录",
            subtitle = state.self?.userId ?: "",
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f),
        ) {
            PurrStatusChip(
                label = if (state.self != null) "已登录" else "未登录",
                detail = if (state.isLoading) "处理中" else "",
                accentColor = if (state.self != null) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
        }

        PurrPanel(title = "操作") {
            PurrPrimaryButton(
                text = if (state.isLoading) "退出中..." else "退出登录",
                onClick = onLogout,
                enabled = !state.isLoading,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            PurrSecondaryButton(
                text = "返回",
                onClick = onBack,
                enabled = !state.isLoading,
            )
        }
    }
}
