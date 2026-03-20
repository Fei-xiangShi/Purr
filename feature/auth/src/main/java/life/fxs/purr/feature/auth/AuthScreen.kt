package life.fxs.purr.feature.auth

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle

@Composable
fun AuthScreenRoute(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AuthEffect.NavigateHome -> onLoginSuccess()
                is AuthEffect.ShowError -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AuthScreen(
        state = state,
        onUsernameChanged = { viewModel.onIntent(AuthIntent.UsernameChanged(it)) },
        onPasswordChanged = { viewModel.onIntent(AuthIntent.PasswordChanged(it)) },
        onSubmit = { viewModel.onIntent(AuthIntent.Submit) },
    )
}

@Composable
fun AuthScreen(
    state: AuthState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    PurrScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        PurrSectionTitle(
            eyebrow = "欢迎",
            title = "登录 Purr",
            subtitle = "请输入账号和密码",
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        )

        PurrPanel(title = "登录") {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
                enabled = !state.isLoading,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                enabled = !state.isLoading,
                visualTransformation = PasswordVisualTransformation(),
            )
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PurrPrimaryButton(
                text = if (state.isLoading) "登录中..." else "登录",
                onClick = onSubmit,
                enabled = !state.isLoading,
            )
        }
    }
}
