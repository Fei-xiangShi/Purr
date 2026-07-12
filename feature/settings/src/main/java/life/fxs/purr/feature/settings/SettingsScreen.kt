package life.fxs.purr.feature.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrAvatar
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton

@Composable
fun SettingsScreenRoute(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val selected = withContext(Dispatchers.IO) { context.readAvatar(uri) }
            withContext(Dispatchers.Main.immediate) {
                if (selected == null) {
                    Toast.makeText(context, "请选择不超过 10 MB 的 JPEG、PNG 或 WebP 图片", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.onIntent(SettingsIntent.AvatarSelected(selected.contentType, selected.bytes))
                }
            }
        }
    }

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
        onIntent = viewModel::onIntent,
        onPickAvatar = { avatarPicker.launch("image/*") },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onIntent: (SettingsIntent) -> Unit,
    onPickAvatar: () -> Unit,
) {
    PurrScreen {
        PurrSectionTitle(
            eyebrow = "设置",
            title = "账户设置",
            subtitle = "",
            modifier = Modifier,
        )

        PurrPanel(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(
                        enabled = state.self != null && !state.isBusy,
                        role = Role.Button,
                        onClick = onPickAvatar,
                    ),
            ) {
                PurrAvatar(
                    avatarUrl = state.self?.avatarUrl,
                    contentDescription = "账户头像",
                    size = 112.dp,
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(34.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isUploadingAvatar) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "选择头像",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { onIntent(SettingsIntent.DisplayNameChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名") },
                singleLine = true,
                enabled = state.self != null && !state.isBusy,
                isError = state.displayNameError != null,
                supportingText = state.displayNameError?.let { message ->
                    { Text(message) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onIntent(SettingsIntent.SubmitDisplayName) },
                ),
            )
            Text(
                text = state.self?.userId.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PurrPrimaryButton(
                text = if (state.isUpdatingDisplayName) "保存中..." else "保存显示名",
                onClick = { onIntent(SettingsIntent.SubmitDisplayName) },
                enabled = state.self != null &&
                    !state.isBusy &&
                    state.displayName.trim().isNotEmpty() &&
                    state.displayName.trim() != state.self.displayName,
            )
        }

        if (state.isPasswordFormVisible) {
            PasswordChangePanel(
                state = state,
                onIntent = onIntent,
            )
        }

        PurrPanel(title = "操作") {
            PurrSecondaryButton(
                text = "修改密码",
                onClick = { onIntent(SettingsIntent.ShowPasswordForm) },
                enabled = state.self != null && !state.isBusy && !state.isPasswordFormVisible,
            )
            PurrPrimaryButton(
                text = if (state.isLoggingOut) "退出中..." else "退出登录",
                onClick = { onIntent(SettingsIntent.Logout) },
                enabled = !state.isBusy,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            PurrSecondaryButton(
                text = "返回",
                onClick = onBack,
                enabled = !state.isBusy,
            )
        }
    }
}

private data class AvatarUpload(val contentType: String, val bytes: ByteArray)

private fun android.content.Context.readAvatar(uri: android.net.Uri): AvatarUpload? {
    val bytes = contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total <= MAX_AVATAR_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_AVATAR_BYTES + 1 - total))
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
        }
        output.toByteArray()
    } ?: return null
    if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) return null
    val contentType = when {
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
        else -> return null
    }
    return AvatarUpload(contentType, bytes)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private const val MAX_AVATAR_BYTES = 10 * 1024 * 1024

@Composable
private fun PasswordChangePanel(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    val fieldsEnabled = !state.isBusy
    val passwordTransformation = PasswordVisualTransformation()
    PurrPanel(title = "修改密码") {
        OutlinedTextField(
            value = state.currentPassword,
            onValueChange = { onIntent(SettingsIntent.CurrentPasswordChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("当前密码") },
            singleLine = true,
            enabled = fieldsEnabled,
            visualTransformation = passwordTransformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
            value = state.newPassword,
            onValueChange = { onIntent(SettingsIntent.NewPasswordChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("新密码") },
            singleLine = true,
            enabled = fieldsEnabled,
            visualTransformation = passwordTransformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
            value = state.confirmPassword,
            onValueChange = { onIntent(SettingsIntent.ConfirmPasswordChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("确认新密码") },
            singleLine = true,
            enabled = fieldsEnabled,
            visualTransformation = passwordTransformation,
            isError = state.passwordError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onIntent(SettingsIntent.SubmitPasswordChange) },
            ),
        )
        state.passwordError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PurrPrimaryButton(
            text = if (state.isChangingPassword) "修改中..." else "确认修改",
            onClick = { onIntent(SettingsIntent.SubmitPasswordChange) },
            enabled = !state.isBusy,
        )
        PurrSecondaryButton(
            text = "取消",
            onClick = { onIntent(SettingsIntent.HidePasswordForm) },
            enabled = fieldsEnabled,
        )
    }
}
