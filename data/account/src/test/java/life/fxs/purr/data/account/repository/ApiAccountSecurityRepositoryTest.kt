package life.fxs.purr.data.account.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.data.account.network.SessionWriter
import org.junit.Test

class ApiAccountSecurityRepositoryTest {
    private val accountApi = mockk<PurrAccountApi>()
    private val sessionWriter = mockk<SessionWriter>()
    private val repository = ApiAccountSecurityRepository(accountApi, sessionWriter)

    @Test
    fun `clears local session after the server accepts the new password`() = runTest {
        coEvery { accountApi.changePassword(any()) } just Runs
        coEvery { sessionWriter.clear() } just Runs

        val result = repository.changePassword("old-password", "new-password")

        assertThat(result).isEqualTo(life.fxs.purr.core.common.AppResult.Success(Unit))
        coVerify(exactly = 1) { accountApi.changePassword(any()) }
        coVerify(exactly = 1) { sessionWriter.clear() }
    }
}
