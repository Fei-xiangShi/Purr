package life.fxs.purr.data.account.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.data.account.network.SessionTokenHolder
import life.fxs.purr.data.account.network.SessionWriter
import org.junit.Test

class ApiAuthRepositoryTest {
    @Test
    fun `late logout completion cannot clear a replacement session`() = runTest {
        val authApi = mockk<PurrAuthApi>()
        val sessionStore = mockk<SessionStore>()
        val sessionWriter = mockk<SessionWriter>()
        val tokenHolder = SessionTokenHolder().apply {
            update(
                accessToken = "access-old",
                refreshToken = "refresh-old",
                userId = "user-a",
            )
        }
        val logoutStarted = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        every { sessionStore.session } returns MutableStateFlow(null)
        coEvery { sessionWriter.syncFromStore(any()) } returns Unit
        coEvery { authApi.logout("Bearer access-old") } coAnswers {
            logoutStarted.complete(Unit)
            releaseLogout.await()
        }
        coEvery { sessionWriter.clearIfSessionMatches(any()) } returns false
        val repository = ApiAuthRepository(
            authApi = authApi,
            sessionStore = sessionStore,
            sessionWriter = sessionWriter,
            sessionTokenHolder = tokenHolder,
            applicationScope = backgroundScope,
        )

        val logout = async { repository.logout() }
        logoutStarted.await()
        tokenHolder.update(
            accessToken = "access-new",
            refreshToken = "refresh-new",
            userId = "user-a",
        )
        releaseLogout.complete(Unit)

        assertThat(logout.await()).isEqualTo(AppResult.Success(Unit))
        assertThat(tokenHolder.accessToken()).isEqualTo("access-new")
        coVerify(exactly = 1) { authApi.logout("Bearer access-old") }
        coVerify(exactly = 1) {
            sessionWriter.clearIfSessionMatches(
                match { session ->
                    session.accessToken == "access-old" &&
                        session.refreshToken == "refresh-old"
                },
            )
        }
    }
}
