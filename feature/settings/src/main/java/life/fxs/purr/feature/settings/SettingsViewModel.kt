package life.fxs.purr.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.account.usecase.ChangePasswordUseCase
import life.fxs.purr.domain.account.usecase.LogoutUseCase
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.UploadAvatarUseCase
import life.fxs.purr.domain.account.usecase.UpdateDisplayNameUseCase

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val changePasswordUseCase: ChangePasswordUseCase,
    private val uploadAvatarUseCase: UploadAvatarUseCase,
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
) : ViewModel() {
    private val sessionState = observeAuthSessionUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            sessionState.collect { session ->
                _state.value = _state.value.copy(
                    self = session?.self,
                    displayName = session?.self?.displayName.orEmpty(),
                )
            }
        }
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.ShowPasswordForm -> updateState {
                copy(isPasswordFormVisible = true, passwordError = null)
            }
            SettingsIntent.HidePasswordForm -> if (!_state.value.isChangingPassword) {
                updateState { clearedPasswordForm() }
            }
            is SettingsIntent.CurrentPasswordChanged -> updateState {
                copy(currentPassword = intent.value, passwordError = null)
            }
            is SettingsIntent.NewPasswordChanged -> updateState {
                copy(newPassword = intent.value, passwordError = null)
            }
            is SettingsIntent.ConfirmPasswordChanged -> updateState {
                copy(confirmPassword = intent.value, passwordError = null)
            }
            SettingsIntent.SubmitPasswordChange -> changePassword()
            is SettingsIntent.AvatarCropConfirmed -> uploadAvatar(intent.contentType, intent.bytes)
            is SettingsIntent.DisplayNameChanged -> updateState {
                copy(displayName = intent.value, displayNameError = null)
            }
            SettingsIntent.SubmitDisplayName -> updateDisplayName()
            SettingsIntent.Logout -> logout()
        }
    }

    private fun updateDisplayName() {
        val displayName = _state.value.displayName.trim()
        val error = when {
            displayName.isEmpty() -> "显示名不能为空"
            displayName.length > MAX_DISPLAY_NAME_LENGTH -> "显示名不能超过 100 个字符"
            displayName.any(Char::isISOControl) -> "显示名不能包含控制字符"
            else -> null
        }
        if (error != null) {
            updateState { copy(displayNameError = error) }
            return
        }
        if (_state.value.isBusy || displayName == _state.value.self?.displayName) return
        viewModelScope.launch {
            updateState { copy(isUpdatingDisplayName = true, displayNameError = null) }
            when (val result = updateDisplayNameUseCase(displayName)) {
                is AppResult.Success -> {
                    updateState {
                        copy(
                            self = result.value,
                            displayName = result.value.displayName,
                            isUpdatingDisplayName = false,
                        )
                    }
                    _effects.emit(SettingsEffect.ShowMessage("显示名修改成功"))
                }
                is AppResult.Failure -> updateState {
                    copy(
                        isUpdatingDisplayName = false,
                        displayNameError = result.error.toUserMessage(),
                    )
                }
            }
        }
    }

    private fun uploadAvatar(contentType: String, bytes: ByteArray) {
        if (_state.value.isBusy) return
        val uploadBytes = bytes.copyOf()
        updateState { copy(isUploadingAvatar = true, avatarError = null) }
        viewModelScope.launch {
            try {
                when (val result = uploadAvatarUseCase(contentType, uploadBytes)) {
                    is AppResult.Success -> {
                        updateState {
                            copy(
                                self = result.value,
                                isUploadingAvatar = false,
                                avatarError = null,
                            )
                        }
                        _effects.emit(SettingsEffect.ShowMessage("头像上传成功"))
                    }
                    is AppResult.Failure -> {
                        val message = result.error.toUserMessage()
                        updateState {
                            copy(
                                isUploadingAvatar = false,
                                avatarError = message,
                            )
                        }
                        _effects.emit(SettingsEffect.ShowMessage(message))
                    }
                }
            } catch (cancellation: CancellationException) {
                updateState { copy(isUploadingAvatar = false) }
                throw cancellation
            } catch (exception: Exception) {
                val message = AppError.Unexpected(exception).toUserMessage()
                updateState {
                    copy(
                        isUploadingAvatar = false,
                        avatarError = message,
                    )
                }
                _effects.emit(SettingsEffect.ShowMessage(message))
            }
        }
    }

    private fun changePassword() {
        val currentState = _state.value
        if (currentState.isBusy) return
        val validationError = PasswordChangeValidator.validate(
            currentPassword = currentState.currentPassword,
            newPassword = currentState.newPassword,
            confirmPassword = currentState.confirmPassword,
        )
        if (validationError != null) {
            updateState { copy(passwordError = validationError) }
            return
        }

        viewModelScope.launch {
            updateState { copy(isChangingPassword = true, passwordError = null) }
            when (val result = changePasswordUseCase(currentState.currentPassword, currentState.newPassword)) {
                is AppResult.Success -> {
                    updateState { clearedPasswordForm() }
                    _effects.emit(SettingsEffect.ShowMessage("密码修改成功，请重新登录"))
                    _effects.emit(SettingsEffect.LoggedOut)
                }
                is AppResult.Failure -> updateState {
                    copy(
                        isChangingPassword = false,
                        passwordError = result.error.toUserMessage(),
                    )
                }
            }
        }
    }

    private fun logout() {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            updateState { copy(isLoggingOut = true) }
            when (val result = logoutUseCase()) {
                is AppResult.Success -> {
                    updateState { copy(isLoggingOut = false) }
                    _effects.emit(SettingsEffect.LoggedOut)
                }
                is AppResult.Failure -> {
                    updateState { copy(isLoggingOut = false) }
                    _effects.emit(SettingsEffect.ShowMessage(result.error.toUserMessage()))
                }
            }
        }
    }

    private inline fun updateState(transform: SettingsState.() -> SettingsState) {
        _state.value = _state.value.transform()
    }

    private fun SettingsState.clearedPasswordForm() = copy(
        isChangingPassword = false,
        isPasswordFormVisible = false,
        currentPassword = "",
        newPassword = "",
        confirmPassword = "",
        passwordError = null,
    )

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 100
    }
}
