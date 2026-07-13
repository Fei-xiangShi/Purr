package life.fxs.purr.data.account.network

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.domain.account.model.AuthSession
import org.junit.Test

class SessionWriterTest {
    private val sessionStore = mockk<SessionStore>()
    private val tokenHolder = SessionTokenHolder()
    private val sessionWriter = SessionWriter(sessionStore, tokenHolder)

    @Test
    fun `conditional clear does not delete a session whose token has rotated`() = runTest {
        tokenHolder.update(
            accessToken = "access-new",
            refreshToken = "refresh-new",
            userId = "user-a",
        )

        val cleared = sessionWriter.clearIfRefreshTokenMatches("refresh-old")

        assertThat(cleared).isFalse()
        assertThat(tokenHolder.refreshToken()).isEqualTo("refresh-new")
        coVerify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `conditional clear removes the matching persisted and in-memory session`() = runTest {
        tokenHolder.update(
            accessToken = "access-old",
            refreshToken = "refresh-old",
            userId = "user-a",
        )
        coEvery { sessionStore.clear() } just Runs

        val cleared = sessionWriter.clearIfRefreshTokenMatches("refresh-old")

        assertThat(cleared).isTrue()
        assertThat(tokenHolder.accessToken()).isNull()
        assertThat(tokenHolder.refreshToken()).isNull()
        coVerify(exactly = 1) { sessionStore.clear() }
    }

    @Test
    fun `conditional persist does not overwrite a session whose token has rotated`() = runTest {
        tokenHolder.update(
            accessToken = "access-newer",
            refreshToken = "refresh-newer",
            userId = "user-a",
        )

        val persisted = sessionWriter.persistIfRefreshTokenMatches(
            expectedRefreshToken = "refresh-old",
            session = refreshedSession(),
        )

        assertThat(persisted).isFalse()
        assertThat(tokenHolder.accessToken()).isEqualTo("access-newer")
        coVerify(exactly = 0) { sessionStore.save(any()) }
    }

    @Test
    fun `conditional persist atomically replaces a matching session`() = runTest {
        tokenHolder.update(
            accessToken = "access-old",
            refreshToken = "refresh-old",
            userId = "user-a",
        )
        coEvery { sessionStore.save(any()) } just Runs
        val refreshed = refreshedSession()

        val persisted = sessionWriter.persistIfRefreshTokenMatches(
            expectedRefreshToken = "refresh-old",
            session = refreshed,
        )

        assertThat(persisted).isTrue()
        assertThat(tokenHolder.accessToken()).isEqualTo(refreshed.accessToken)
        assertThat(tokenHolder.refreshToken()).isEqualTo(refreshed.refreshToken)
        coVerify(exactly = 1) { sessionStore.save(refreshed) }
    }

    @Test
    fun `profile persistence retains the latest rotated tokens`() = runTest {
        val rotated = refreshedSession()
        val updatedProfile = rotated.self.copy(
            displayName = "Updated User",
            avatarUrl = "https://storage.example/avatar.png",
        )
        val savedSession = slot<AuthSession>()
        tokenHolder.update(
            accessToken = rotated.accessToken,
            refreshToken = rotated.refreshToken,
            userId = rotated.self.userId,
        )
        coEvery { sessionStore.readCurrentSession() } returns rotated
        coEvery { sessionStore.save(capture(savedSession)) } just Runs

        val persisted = sessionWriter.persistProfile(updatedProfile)

        assertThat(persisted).isTrue()
        assertThat(savedSession.captured.accessToken).isEqualTo(rotated.accessToken)
        assertThat(savedSession.captured.refreshToken).isEqualTo(rotated.refreshToken)
        assertThat(savedSession.captured.self).isEqualTo(updatedProfile)
        assertThat(tokenHolder.accessToken()).isEqualTo(rotated.accessToken)
        assertThat(tokenHolder.refreshToken()).isEqualTo(rotated.refreshToken)
    }

    @Test
    fun `conditional session clear cannot remove a concurrently replaced session`() = runTest {
        tokenHolder.update(
            accessToken = "access-old",
            refreshToken = "refresh-old",
            userId = "user-a",
        )
        val sessionAtRequestStart = tokenHolder.snapshot()
        tokenHolder.update(
            accessToken = "access-new",
            refreshToken = "refresh-new",
            userId = "user-a",
        )

        val cleared = sessionWriter.clearIfSessionMatches(sessionAtRequestStart)

        assertThat(cleared).isFalse()
        assertThat(tokenHolder.accessToken()).isEqualTo("access-new")
        assertThat(tokenHolder.refreshToken()).isEqualTo("refresh-new")
        coVerify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `queued stale store emission cannot roll holder back after persist`() = runTest {
        val oldSession = oldSession()
        val refreshed = refreshedSession()
        tokenHolder.update(
            accessToken = oldSession.accessToken,
            refreshToken = oldSession.refreshToken,
            userId = oldSession.self.userId,
        )
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        coEvery { sessionStore.save(refreshed) } coAnswers {
            saveStarted.complete(Unit)
            releaseSave.await()
        }
        coEvery { sessionStore.readCurrentSession() } returns refreshed

        val persist = async { sessionWriter.persist(refreshed) }
        saveStarted.await()
        val staleSync = async { sessionWriter.syncFromStore(oldSession) }
        kotlinx.coroutines.yield()
        releaseSave.complete(Unit)
        persist.await()
        staleSync.await()

        assertThat(tokenHolder.accessToken()).isEqualTo(refreshed.accessToken)
        assertThat(tokenHolder.refreshToken()).isEqualTo(refreshed.refreshToken)

        val staleRejectionCleared = sessionWriter.clearIfRefreshTokenMatches(
            oldSession.refreshToken,
        )
        assertThat(staleRejectionCleared).isFalse()
        coVerify(exactly = 0) { sessionStore.clear() }
        coVerify(exactly = 1) { sessionStore.readCurrentSession() }
    }

    @Test
    fun `store emission initializes an empty token holder after readback validation`() = runTest {
        val stored = oldSession()
        coEvery { sessionStore.readCurrentSession() } returns stored

        sessionWriter.syncFromStore(stored)

        assertThat(tokenHolder.accessToken()).isEqualTo(stored.accessToken)
        assertThat(tokenHolder.refreshToken()).isEqualTo(stored.refreshToken)
        coVerify(exactly = 1) { sessionStore.readCurrentSession() }
    }

    @Test
    fun `temporary store read failure does not clear the current token holder`() = runTest {
        val stored = oldSession()
        tokenHolder.update(
            accessToken = stored.accessToken,
            refreshToken = stored.refreshToken,
            userId = stored.self.userId,
        )
        coEvery { sessionStore.readCurrentSession() } throws IOException("store unavailable")

        sessionWriter.syncFromStore(null)

        assertThat(tokenHolder.accessToken()).isEqualTo(stored.accessToken)
        assertThat(tokenHolder.refreshToken()).isEqualTo(stored.refreshToken)
        coVerify(exactly = 0) { sessionStore.clear() }
    }

    private fun oldSession(): AuthSession = AuthSession(
        accessToken = "access-old",
        refreshToken = "refresh-old",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )

    private fun refreshedSession(): AuthSession = AuthSession(
        accessToken = "access-refreshed",
        refreshToken = "refresh-refreshed",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )
}
