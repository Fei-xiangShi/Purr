package life.fxs.purr.platform.push

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RegisterPushInstallationUseCase
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushDeviceRegistrationCoordinatorTest {
    @Test
    fun `auth restoration token refresh and session rotation resynchronize registration`() = runTest {
        val auth = MutableStateFlow<AuthSession?>(null)
        val tokens = FakePushTokenStore(TOKEN_A)
        val observeAuth = mockk<ObserveAuthSessionUseCase>()
        val register = mockk<RegisterPushInstallationUseCase>()
        every { observeAuth.invoke() } returns auth
        coEvery { register.invoke(INSTALLATION_ID, any()) } returns AppResult.Success(Unit)
        val coordinator = PushDeviceRegistrationCoordinator(
            observeAuthSession = observeAuth,
            tokenStore = tokens,
            installationIdStore = FixedInstallationIdStore,
            registerInstallation = register,
            applicationScope = backgroundScope,
        )

        coordinator.start()
        coordinator.start()
        runCurrent()
        coVerify(exactly = 0) { register.invoke(any(), any()) }

        auth.value = session("refresh-a")
        runCurrent()
        coVerify(exactly = 1) { register.invoke(INSTALLATION_ID, TOKEN_A) }

        tokens.update(TOKEN_B)
        runCurrent()
        coVerify(exactly = 1) { register.invoke(INSTALLATION_ID, TOKEN_B) }

        auth.value = session("refresh-b")
        runCurrent()
        coVerify(exactly = 2) { register.invoke(INSTALLATION_ID, TOKEN_B) }
    }

    private fun session(refreshToken: String) = AuthSession(
        accessToken = "access-token",
        refreshToken = refreshToken,
        self = SelfProfile("user-a", "User A"),
    )

    private class FakePushTokenStore(initial: String?) : PushTokenStore {
        private val state = MutableStateFlow(initial)

        override fun observe(): StateFlow<String?> = state

        override fun update(token: String) {
            state.value = token
        }
    }

    private data object FixedInstallationIdStore : InstallationIdStore {
        override fun getOrCreate(): String = INSTALLATION_ID
    }

    private companion object {
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val TOKEN_A = "fcm-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TOKEN_B = "fcm-token-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
