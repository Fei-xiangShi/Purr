package life.fxs.purr.data.account.network

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.core.network.model.AuthSessionDto
import life.fxs.purr.domain.account.model.AuthSession
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RefreshSessionCoordinatorTest {
    private val authApi = mockk<PurrAuthApi>()
    private val tokenHolder = SessionTokenHolder()
    private val sessionWriter = mockk<SessionWriter>()
    private val coordinator = RefreshSessionCoordinator(authApi, tokenHolder, sessionWriter)

    @Test
    fun `timeout keeps session and a later request can retry`() = runTest {
        seedSession()
        var attempts = 0
        coEvery { authApi.refresh(any()) } coAnswers {
            if (attempts++ == 0) throw SocketTimeoutException("timed out")
            REFRESHED_SESSION
        }
        persistSessionIntoHolder()

        val timedOut = coordinator.refresh(OLD_REFRESH_TOKEN)
        val retried = coordinator.refresh(OLD_REFRESH_TOKEN)

        assertThat(timedOut).isEqualTo(RefreshSessionResult.TemporarilyUnavailable)
        assertThat(retried).isEqualTo(
            RefreshSessionResult.Authenticated(REFRESHED_SESSION.accessToken),
        )
        assertThat(tokenHolder.refreshToken()).isEqualTo(REFRESHED_SESSION.refreshToken)
        coVerify(exactly = 2) { authApi.refresh(any()) }
        coVerify(exactly = 0) { sessionWriter.clearIfRefreshTokenMatches(any()) }
    }

    @Test
    fun `io failure remains retryable and does not clear credentials`() = runTest {
        seedSession()
        coEvery { authApi.refresh(any()) } throws IOException("offline")

        val result = coordinator.refresh(OLD_REFRESH_TOKEN)

        assertThat(result).isEqualTo(RefreshSessionResult.TemporarilyUnavailable)
        assertThat(tokenHolder.accessToken()).isEqualTo(OLD_ACCESS_TOKEN)
        assertThat(tokenHolder.refreshToken()).isEqualTo(OLD_REFRESH_TOKEN)
        coVerify(exactly = 0) { sessionWriter.clearIfRefreshTokenMatches(any()) }
    }

    @Test
    fun `rate limit response remains retryable and does not clear credentials`() = runTest {
        seedSession()
        coEvery { authApi.refresh(any()) } throws httpFailure(429)

        val result = coordinator.refresh(OLD_REFRESH_TOKEN)

        assertThat(result).isEqualTo(RefreshSessionResult.TemporarilyUnavailable)
        assertThat(tokenHolder.refreshToken()).isEqualTo(OLD_REFRESH_TOKEN)
        coVerify(exactly = 0) { sessionWriter.clearIfRefreshTokenMatches(any()) }
    }

    @Test
    fun `cancellation propagates without clearing credentials`() = runTest {
        seedSession()
        coEvery { authApi.refresh(any()) } throws CancellationException("cancelled")

        var cancellation: CancellationException? = null
        try {
            coordinator.refresh(OLD_REFRESH_TOKEN)
        } catch (failure: CancellationException) {
            cancellation = failure
        }

        assertThat(cancellation).isNotNull()
        assertThat(tokenHolder.refreshToken()).isEqualTo(OLD_REFRESH_TOKEN)
        coVerify(exactly = 0) { sessionWriter.clearIfRefreshTokenMatches(any()) }
    }

    @Test
    fun `server rejection clears only the rejected session`() = runTest {
        seedSession()
        coEvery { authApi.refresh(any()) } throws unauthorizedResponse()
        coEvery { sessionWriter.clearIfRefreshTokenMatches(OLD_REFRESH_TOKEN) } coAnswers {
            tokenHolder.clear()
            true
        }

        val result = coordinator.refresh(OLD_REFRESH_TOKEN)

        assertThat(result).isEqualTo(RefreshSessionResult.RefreshTokenRejected)
        assertThat(tokenHolder.accessToken()).isNull()
        assertThat(tokenHolder.refreshToken()).isNull()
        coVerify(exactly = 1) {
            sessionWriter.clearIfRefreshTokenMatches(OLD_REFRESH_TOKEN)
        }
    }

    @Test
    fun `late rejection cannot clear a newer session`() = runTest {
        seedSession()
        coEvery { authApi.refresh(any()) } coAnswers {
            tokenHolder.update(
                accessToken = NEWER_ACCESS_TOKEN,
                refreshToken = NEWER_REFRESH_TOKEN,
                userId = USER_ID,
            )
            throw unauthorizedResponse()
        }
        coEvery { sessionWriter.clearIfRefreshTokenMatches(OLD_REFRESH_TOKEN) } returns false

        val result = coordinator.refresh(OLD_REFRESH_TOKEN)

        assertThat(result).isEqualTo(RefreshSessionResult.Authenticated(NEWER_ACCESS_TOKEN))
        assertThat(tokenHolder.refreshToken()).isEqualTo(NEWER_REFRESH_TOKEN)
        coVerify(exactly = 1) {
            sessionWriter.clearIfRefreshTokenMatches(OLD_REFRESH_TOKEN)
        }
    }

    @Test
    fun `late successful refresh cannot overwrite a newer session`() = runTest {
        seedSession()
        coEvery { authApi.refresh(any()) } coAnswers {
            tokenHolder.update(
                accessToken = NEWER_ACCESS_TOKEN,
                refreshToken = NEWER_REFRESH_TOKEN,
                userId = USER_ID,
            )
            REFRESHED_SESSION
        }
        coEvery {
            sessionWriter.persistIfRefreshTokenMatches(OLD_REFRESH_TOKEN, any())
        } returns false

        val result = coordinator.refresh(OLD_REFRESH_TOKEN)

        assertThat(result).isEqualTo(RefreshSessionResult.Authenticated(NEWER_ACCESS_TOKEN))
        assertThat(tokenHolder.refreshToken()).isEqualTo(NEWER_REFRESH_TOKEN)
        coVerify(exactly = 1) {
            sessionWriter.persistIfRefreshTokenMatches(OLD_REFRESH_TOKEN, any())
        }
    }

    @Test
    fun `concurrent refreshes share one network request`() = runTest {
        seedSession()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        coEvery { authApi.refresh(any()) } coAnswers {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            REFRESHED_SESSION
        }
        persistSessionIntoHolder()

        val first = async { coordinator.refresh(OLD_REFRESH_TOKEN) }
        refreshStarted.await()
        val second = async { coordinator.refresh(OLD_REFRESH_TOKEN) }
        kotlinx.coroutines.yield()
        releaseRefresh.complete(Unit)

        assertThat(listOf(first.await(), second.await())).containsExactly(
            RefreshSessionResult.Authenticated(REFRESHED_SESSION.accessToken),
            RefreshSessionResult.Authenticated(REFRESHED_SESSION.accessToken),
        )
        coVerify(exactly = 1) { authApi.refresh(any()) }
        coVerify(exactly = 1) {
            sessionWriter.persistIfRefreshTokenMatches(OLD_REFRESH_TOKEN, any())
        }
    }

    @Test
    fun `concurrent callers share the same temporary failure`() = runTest {
        seedSession()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        coEvery { authApi.refresh(any()) } coAnswers {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            throw SocketTimeoutException("timed out")
        }

        val first = async { coordinator.refresh(OLD_REFRESH_TOKEN) }
        refreshStarted.await()
        val second = async { coordinator.refresh(OLD_REFRESH_TOKEN) }
        kotlinx.coroutines.yield()
        releaseRefresh.complete(Unit)

        assertThat(listOf(first.await(), second.await())).containsExactly(
            RefreshSessionResult.TemporarilyUnavailable,
            RefreshSessionResult.TemporarilyUnavailable,
        )
        coVerify(exactly = 1) { authApi.refresh(any()) }
        coVerify(exactly = 0) { sessionWriter.clearIfRefreshTokenMatches(any()) }
    }

    private fun seedSession() {
        tokenHolder.update(
            accessToken = OLD_ACCESS_TOKEN,
            refreshToken = OLD_REFRESH_TOKEN,
            userId = USER_ID,
        )
    }

    private fun persistSessionIntoHolder() {
        coEvery {
            sessionWriter.persistIfRefreshTokenMatches(OLD_REFRESH_TOKEN, any())
        } coAnswers {
            val session = secondArg<AuthSession>()
            tokenHolder.update(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.self.userId,
            )
            true
        }
    }

    private fun unauthorizedResponse(): HttpException = httpFailure(401)

    private fun httpFailure(statusCode: Int): HttpException = HttpException(
        Response.error<String>(statusCode, "request failed".toResponseBody()),
    )

    private companion object {
        const val USER_ID = "user-a"
        const val OLD_ACCESS_TOKEN = "access-old"
        const val OLD_REFRESH_TOKEN = "refresh-old"
        const val NEWER_ACCESS_TOKEN = "access-newer"
        const val NEWER_REFRESH_TOKEN = "refresh-newer"

        val REFRESHED_SESSION = AuthSessionDto(
            accessToken = "access-refreshed",
            refreshToken = "refresh-refreshed",
            self = SelfProfile(userId = USER_ID, displayName = "User A"),
        )
    }
}
