package life.fxs.purr.feature.settings

import life.fxs.purr.core.model.SelfProfile

sealed interface SettingsIntent {
    data object ShowPasswordForm : SettingsIntent
    data object HidePasswordForm : SettingsIntent
    data class CurrentPasswordChanged(val value: String) : SettingsIntent
    data class NewPasswordChanged(val value: String) : SettingsIntent
    data class ConfirmPasswordChanged(val value: String) : SettingsIntent
    data object SubmitPasswordChange : SettingsIntent
    data class AvatarSelected(val contentType: String, val bytes: ByteArray) : SettingsIntent
    data class DisplayNameChanged(val value: String) : SettingsIntent
    data object SubmitDisplayName : SettingsIntent
    data object Logout : SettingsIntent
}

data class SettingsState(
    val self: SelfProfile? = null,
    val isLoggingOut: Boolean = false,
    val isChangingPassword: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val isUpdatingDisplayName: Boolean = false,
    val isPasswordFormVisible: Boolean = false,
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val passwordError: String? = null,
    val displayName: String = "",
    val displayNameError: String? = null,
) {
    val isBusy: Boolean
        get() = isLoggingOut || isChangingPassword || isUploadingAvatar || isUpdatingDisplayName
}

sealed interface SettingsEffect {
    data object LoggedOut : SettingsEffect
    data class ShowMessage(val message: String) : SettingsEffect
}
