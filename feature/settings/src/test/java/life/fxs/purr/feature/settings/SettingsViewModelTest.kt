package life.fxs.purr.feature.settings

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ChangePasswordUseCase
import life.fxs.purr.domain.account.usecase.LogoutUseCase
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.UploadAvatarUseCase
import life.fxs.purr.domain.account.usecase.UpdateDisplayNameUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authState = MutableStateFlow<AuthSession?>(session())
    private val observeAuthSessionUseCase = mockk<ObserveAuthSessionUseCase>()
    private val logoutUseCase = mockk<LogoutUseCase>()
    private val changePasswordUseCase = mockk<ChangePasswordUseCase>()
    private val uploadAvatarUseCase = mockk<UploadAvatarUseCase>()
    private val updateDisplayNameUseCase = mockk<UpdateDisplayNameUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { observeAuthSessionUseCase.invoke() } returns authState
        coEvery { logoutUseCase.invoke() } returns AppResult.Success(Unit)
        coEvery { changePasswordUseCase.invoke(any(), any()) } returns AppResult.Success(Unit)
        coEvery { uploadAvatarUseCase.invoke(any(), any()) } returns AppResult.Success(SelfProfile("user-a", "User A", "https://avatar.test/a.png"))
        coEvery { updateDisplayNameUseCase.invoke(any()) } returns AppResult.Success(SelfProfile("user-a", "New Name"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid confirmation is rejected before calling the use case`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()
            viewModel.enterPasswords(confirmPassword = "different-password")

            viewModel.onIntent(SettingsIntent.SubmitPasswordChange)
            runCurrent()

            assertThat(viewModel.state.value.passwordError).isEqualTo("两次输入的新密码不一致")
            coVerify(exactly = 0) { changePasswordUseCase.invoke(any(), any()) }
        }
    }

    @Test
    fun `successful password change clears secrets and requests login`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()
            val effects = async { viewModel.effects.take(2).toList() }
            runCurrent()
            viewModel.enterPasswords()

            viewModel.onIntent(SettingsIntent.SubmitPasswordChange)
            runCurrent()

            assertThat(effects.await()).containsExactly(
                SettingsEffect.ShowMessage("密码修改成功，请重新登录"),
                SettingsEffect.LoggedOut,
            ).inOrder()
            assertThat(viewModel.state.value.currentPassword).isEmpty()
            assertThat(viewModel.state.value.newPassword).isEmpty()
            assertThat(viewModel.state.value.confirmPassword).isEmpty()
            assertThat(viewModel.state.value.isPasswordFormVisible).isFalse()
            coVerify(exactly = 1) { changePasswordUseCase.invoke("old-password", "new-password") }
        }
    }

    @Test
    fun `failed password change keeps the form available`() = runTest(dispatcher) {
        coEvery { changePasswordUseCase.invoke(any(), any()) } returns
            AppResult.Failure(AppError.Validation("当前密码错误"))
        withViewModel { viewModel ->
            runCurrent()
            viewModel.enterPasswords()

            viewModel.onIntent(SettingsIntent.SubmitPasswordChange)
            runCurrent()

            assertThat(viewModel.state.value.passwordError).isEqualTo("当前密码错误")
            assertThat(viewModel.state.value.isPasswordFormVisible).isTrue()
            assertThat(viewModel.state.value.isChangingPassword).isFalse()
        }
    }

    @Test
    fun `confirmed avatar crop uploads processed bytes and refreshes profile`() = runTest(dispatcher) {
        val image = byteArrayOf(1, 2, 3)
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.take(1).toList() }
            runCurrent()

            viewModel.onIntent(SettingsIntent.AvatarCropConfirmed("image/png", image))
            runCurrent()

            assertThat(viewModel.state.value.self?.avatarUrl).isEqualTo("https://avatar.test/a.png")
            assertThat(viewModel.state.value.isUploadingAvatar).isFalse()
            assertThat(effect.await()).containsExactly(SettingsEffect.ShowMessage("头像上传成功"))
            coVerify(exactly = 1) { uploadAvatarUseCase.invoke("image/png", image) }
            coVerify(exactly = 0) { logoutUseCase.invoke() }
            coVerify(exactly = 0) { changePasswordUseCase.invoke(any(), any()) }
        }
    }

    @Test
    fun `failed avatar upload exits busy state and can be retried`() = runTest(dispatcher) {
        coEvery { uploadAvatarUseCase.invoke(any(), any()) } returns
            AppResult.Failure(AppError.Network("上传失败")) andThen
            AppResult.Success(SelfProfile("user-a", "User A", "https://avatar.test/retry.png"))
        val image = byteArrayOf(1, 2, 3)
        withViewModel { viewModel ->
            runCurrent()
            val effects = async { viewModel.effects.take(2).toList() }
            runCurrent()

            viewModel.onIntent(SettingsIntent.AvatarCropConfirmed("image/png", image))
            runCurrent()

            assertThat(viewModel.state.value.isUploadingAvatar).isFalse()
            assertThat(viewModel.state.value.avatarError).isEqualTo("上传失败")

            viewModel.onIntent(SettingsIntent.AvatarCropConfirmed("image/png", image))
            runCurrent()

            assertThat(viewModel.state.value.isUploadingAvatar).isFalse()
            assertThat(viewModel.state.value.avatarError).isNull()
            assertThat(viewModel.state.value.self?.avatarUrl).isEqualTo("https://avatar.test/retry.png")
            assertThat(effects.await()).containsExactly(
                SettingsEffect.ShowMessage("上传失败"),
                SettingsEffect.ShowMessage("头像上传成功"),
            ).inOrder()
            coVerify(exactly = 2) { uploadAvatarUseCase.invoke("image/png", any()) }
        }
    }

    @Test
    fun `repeated crop confirmation while uploading starts only one request`() = runTest(dispatcher) {
        val result = CompletableDeferred<AppResult<SelfProfile>>()
        coEvery { uploadAvatarUseCase.invoke(any(), any()) } coAnswers { result.await() }
        val firstImage = byteArrayOf(1, 2, 3)
        val secondImage = byteArrayOf(4, 5, 6)
        withViewModel { viewModel ->
            runCurrent()

            viewModel.onIntent(SettingsIntent.AvatarCropConfirmed("image/png", firstImage))
            viewModel.onIntent(SettingsIntent.AvatarCropConfirmed("image/png", secondImage))
            runCurrent()

            assertThat(viewModel.state.value.isUploadingAvatar).isTrue()
            coVerify(exactly = 1) { uploadAvatarUseCase.invoke("image/png", match { it.contentEquals(firstImage) }) }

            result.complete(AppResult.Success(SelfProfile("user-a", "User A", "https://avatar.test/a.png")))
            runCurrent()

            assertThat(viewModel.state.value.isUploadingAvatar).isFalse()
        }
    }

    @Test
    fun `cancelled avatar upload exits busy state`() = runTest(dispatcher) {
        coEvery { uploadAvatarUseCase.invoke(any(), any()) } throws CancellationException("cancelled")
        withViewModel { viewModel ->
            runCurrent()

            viewModel.onIntent(SettingsIntent.AvatarCropConfirmed("image/jpeg", byteArrayOf(1)))
            runCurrent()

            assertThat(viewModel.state.value.isUploadingAvatar).isFalse()
        }
    }

    @Test
    fun `successful display name update refreshes profile`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.take(1).toList() }
            runCurrent()

            viewModel.onIntent(SettingsIntent.DisplayNameChanged(" New Name "))
            viewModel.onIntent(SettingsIntent.SubmitDisplayName)
            runCurrent()

            assertThat(viewModel.state.value.self?.displayName).isEqualTo("New Name")
            assertThat(viewModel.state.value.displayName).isEqualTo("New Name")
            assertThat(effect.await()).containsExactly(SettingsEffect.ShowMessage("显示名修改成功"))
            coVerify(exactly = 1) { updateDisplayNameUseCase.invoke("New Name") }
        }
    }

    private fun SettingsViewModel.enterPasswords(confirmPassword: String = "new-password") {
        onIntent(SettingsIntent.ShowPasswordForm)
        onIntent(SettingsIntent.CurrentPasswordChanged("old-password"))
        onIntent(SettingsIntent.NewPasswordChanged("new-password"))
        onIntent(SettingsIntent.ConfirmPasswordChanged(confirmPassword))
    }

    private suspend inline fun withViewModel(block: suspend (SettingsViewModel) -> Unit) {
        val viewModel = SettingsViewModel(
            observeAuthSessionUseCase = observeAuthSessionUseCase,
            logoutUseCase = logoutUseCase,
            changePasswordUseCase = changePasswordUseCase,
            uploadAvatarUseCase = uploadAvatarUseCase,
            updateDisplayNameUseCase = updateDisplayNameUseCase,
        )
        try {
            block(viewModel)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private companion object {
        fun session() = AuthSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            self = SelfProfile(userId = "user-a", displayName = "User A"),
        )
    }
}
