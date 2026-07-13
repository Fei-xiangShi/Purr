package life.fxs.purr.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import life.fxs.purr.core.designsystem.component.PurrAvatar
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.feature.settings.avatar.AvatarCropExporter
import life.fxs.purr.feature.settings.avatar.AvatarImageDecoder
import life.fxs.purr.feature.settings.avatar.AvatarImageFailure
import life.fxs.purr.feature.settings.avatar.AvatarImageProcessingException
import life.fxs.purr.feature.settings.avatar.DecodedAvatarImage

@Composable
fun SettingsScreenRoute(
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageDecoder = remember(context.applicationContext) {
        AvatarImageDecoder(context.applicationContext.contentResolver)
    }
    val cropExporter = remember { AvatarCropExporter() }
    var selectedAvatarUri by rememberSaveable { mutableStateOf<String?>(null) }
    var decodedAvatar by remember { mutableStateOf<DecodedAvatarImage?>(null) }
    var isDecodingAvatar by remember { mutableStateOf(false) }
    var isExportingAvatar by remember { mutableStateOf(false) }
    var avatarProcessingError by rememberSaveable { mutableStateOf<String?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            avatarProcessingError = null
            selectedAvatarUri = uri.toString()
        }
    }

    LaunchedEffect(selectedAvatarUri, imageDecoder) {
        val uri = selectedAvatarUri?.let(Uri::parse) ?: return@LaunchedEffect
        isDecodingAvatar = true
        try {
            decodedAvatar = withContext(Dispatchers.IO) { imageDecoder.decode(uri) }
            avatarProcessingError = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AvatarImageProcessingException) {
            selectedAvatarUri = null
            avatarProcessingError = exception.failure.userMessage()
        } catch (_: RuntimeException) {
            selectedAvatarUri = null
            avatarProcessingError = GENERIC_AVATAR_ERROR
        } finally {
            isDecodingAvatar = false
        }
    }

    val exportingState by rememberUpdatedState(isExportingAvatar)
    androidx.compose.runtime.DisposableEffect(decodedAvatar?.bitmap) {
        val decodedImage = decodedAvatar
        onDispose {
            // A non-cooperative bitmap export may still be running after composition disposal.
            // Leave that bitmap for GC in that case; recycling it here could race the exporter.
            if (!exportingState) decodedImage?.close()
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

    SettingsNavHost(
        state = state,
        onIntent = viewModel::onIntent,
        onPickAvatar = {
            avatarProcessingError = null
            avatarPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        isPreparingAvatar = isDecodingAvatar,
        avatarProcessingError = avatarProcessingError,
        decodedAvatar = decodedAvatar,
        hasPendingAvatarSelection = selectedAvatarUri != null,
        isExportingAvatar = isExportingAvatar,
        onCancelPendingAvatar = {
            selectedAvatarUri = null
            avatarProcessingError = null
        },
        onCropDisposed = { image ->
            if (decodedAvatar === image) {
                decodedAvatar = null
                selectedAvatarUri = null
                avatarProcessingError = null
            }
        },
        onCropConfirm = { image, cropArea ->
            if (isExportingAvatar) return@SettingsNavHost false
            isExportingAvatar = true
            try {
                val payload = withContext(Dispatchers.Default) {
                    cropExporter.export(image.bitmap, cropArea)
                }
                avatarProcessingError = null
                viewModel.onIntent(
                    SettingsIntent.AvatarCropConfirmed(
                        contentType = payload.contentType,
                        bytes = payload.bytes,
                    ),
                )
                true
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: AvatarImageProcessingException) {
                avatarProcessingError = exception.failure.userMessage()
                false
            } catch (_: RuntimeException) {
                avatarProcessingError = GENERIC_AVATAR_ERROR
                false
            } finally {
                isExportingAvatar = false
            }
        },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    onPickAvatar: () -> Unit,
    isPreparingAvatar: Boolean = false,
    avatarProcessingError: String? = null,
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
                        enabled = state.self != null && !state.isBusy && !isPreparingAvatar,
                        role = Role.Button,
                        onClickLabel = "选择新头像",
                        onClick = onPickAvatar,
                    )
                    .semantics { contentDescription = "更换账户头像" },
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
                        if (state.isUploadingAvatar || isPreparingAvatar) {
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
            (avatarProcessingError ?: state.avatarError)?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
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
            PurrPrimaryButton(
                text = if (state.isUpdatingDisplayName) "保存中..." else "保存显示名",
                onClick = { onIntent(SettingsIntent.SubmitDisplayName) },
                enabled = state.self != null &&
                    !state.isBusy &&
                    state.displayName.trim().isNotEmpty() &&
                    state.displayName.trim() != state.self.displayName,
            )
            if (state.isPasswordFormVisible) {
                PasswordChangeFields(
                    state = state,
                    onIntent = onIntent,
                )
            } else {
                PurrSecondaryButton(
                    text = "修改密码",
                    onClick = { onIntent(SettingsIntent.ShowPasswordForm) },
                    enabled = state.self != null && !state.isBusy,
                )
            }
        }

        PurrPrimaryButton(
            text = if (state.isLoggingOut) "退出中..." else "退出登录",
            onClick = { onIntent(SettingsIntent.Logout) },
            enabled = !state.isBusy,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private fun AvatarImageFailure.userMessage(): String = when (this) {
    AvatarImageFailure.CANNOT_READ -> "无法读取所选图片，请重新选择"
    AvatarImageFailure.INVALID_IMAGE -> "请选择有效的图片文件"
    AvatarImageFailure.SOURCE_TOO_LARGE -> "图片过大，请选择较小的图片"
    AvatarImageFailure.OUTPUT_TOO_LARGE -> "裁剪后的头像文件过大"
    AvatarImageFailure.ENCODING_FAILED -> GENERIC_AVATAR_ERROR
}

private const val GENERIC_AVATAR_ERROR = "头像处理失败，请重试"

@Composable
private fun PasswordChangeFields(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    Text(
        text = "修改密码",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val fieldsEnabled = !state.isBusy
    val passwordTransformation = PasswordVisualTransformation()
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
