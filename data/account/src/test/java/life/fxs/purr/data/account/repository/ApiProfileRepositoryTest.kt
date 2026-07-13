package life.fxs.purr.data.account.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.data.account.network.SessionWriter
import okhttp3.MultipartBody
import org.junit.Test

class ApiProfileRepositoryTest {
    private val accountApi = mockk<PurrAccountApi>()
    private val sessionWriter = mockk<SessionWriter>()
    private val repository = ApiProfileRepository(accountApi, sessionWriter)

    @Test
    fun `uploads multipart avatar and persists refreshed profile`() = runTest {
        val part = slot<MultipartBody.Part>()
        val updated = SelfProfile(
            userId = "user-a",
            displayName = "User A",
            avatarUrl = "https://storage/avatars/a.png",
        )
        coEvery { accountApi.uploadAvatar(capture(part)) } returns updated
        coEvery { sessionWriter.persistProfile(updated) } returns true

        val result = repository.uploadAvatar("image/png", byteArrayOf(1, 2, 3))

        assertThat(result).isEqualTo(AppResult.Success(updated))
        assertThat(part.captured.headers?.get("Content-Disposition")).contains("name=\"avatar\"")
        assertThat(part.captured.body.contentType().toString()).isEqualTo("image/png")
        coVerify(exactly = 1) { sessionWriter.persistProfile(updated) }
    }
}
