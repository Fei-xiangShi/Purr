package life.fxs.purr.data.account.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.data.account.network.SessionWriter
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.repository.AuthRepository
import okhttp3.MultipartBody
import org.junit.Test

class ApiProfileRepositoryTest {
    private val accountApi = mockk<PurrAccountApi>()
    private val authRepository = mockk<AuthRepository>()
    private val sessionWriter = mockk<SessionWriter>()
    private val repository = ApiProfileRepository(accountApi, authRepository, sessionWriter)

    @Test
    fun `uploads multipart avatar and persists refreshed profile`() = runTest {
        val part = slot<MultipartBody.Part>()
        val persisted = slot<AuthSession>()
        val current = AuthSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            self = SelfProfile("user-a", "User A"),
        )
        val updated = current.self.copy(avatarUrl = "https://storage/avatars/a.png")
        coEvery { accountApi.uploadAvatar(capture(part)) } returns updated
        every { authRepository.currentSession() } returns current
        coEvery { sessionWriter.persist(capture(persisted)) } just Runs

        val result = repository.uploadAvatar("image/png", byteArrayOf(1, 2, 3))

        assertThat(result).isEqualTo(AppResult.Success(updated))
        assertThat(part.captured.headers?.get("Content-Disposition")).contains("name=\"avatar\"")
        assertThat(part.captured.body.contentType().toString()).isEqualTo("image/png")
        assertThat(persisted.captured.self).isEqualTo(updated)
        assertThat(persisted.captured.accessToken).isEqualTo("access-token")
        coVerify(exactly = 1) { sessionWriter.persist(any()) }
    }
}
