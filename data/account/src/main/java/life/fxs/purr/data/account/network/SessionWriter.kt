package life.fxs.purr.data.account.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.domain.account.model.AuthSession

@Singleton
class SessionWriter @Inject constructor(
    private val sessionStore: SessionStore,
    private val tokenHolder: SessionTokenHolder,
) {
    private val mutex = Mutex()

    suspend fun persist(session: AuthSession) = mutex.withLock {
        persistLocked(session)
    }

    suspend fun persistIfRefreshTokenMatches(
        expectedRefreshToken: String,
        session: AuthSession,
    ): Boolean = mutex.withLock {
        if (tokenHolder.refreshToken() != expectedRefreshToken) return@withLock false

        persistLocked(session)
        true
    }

    suspend fun persistProfile(profile: SelfProfile): Boolean = mutex.withLock {
        val currentSession = sessionStore.readCurrentSession() ?: return@withLock false
        if (currentSession.self.userId != profile.userId) return@withLock false

        persistLocked(currentSession.copy(self = profile))
        true
    }

    suspend fun syncFromStore(session: AuthSession?) = mutex.withLock {
        if (tokenHolder.snapshot().matches(session)) return@withLock
        // Flow delivery can lag behind a completed DataStore write; reject stale emissions.
        val currentSession = try {
            sessionStore.readCurrentSession()
        } catch (failure: IOException) {
            // A transient DataStore failure is not a sign-out event. Keep the
            // last confirmed holder state and let a later emission retry.
            return@withLock
        }
        if (currentSession != session) return@withLock

        if (session == null) {
            tokenHolder.clear()
        } else {
            updateTokenHolder(session)
        }
    }

    suspend fun clear() = mutex.withLock {
        sessionStore.clear()
        tokenHolder.clear()
    }

    suspend fun clearIfRefreshTokenMatches(expectedRefreshToken: String): Boolean = mutex.withLock {
        if (tokenHolder.refreshToken() != expectedRefreshToken) return@withLock false

        sessionStore.clear()
        tokenHolder.clear()
        true
    }

    internal suspend fun clearIfSessionMatches(
        expectedSession: SessionTokenHolder.SessionSnapshot,
    ): Boolean = mutex.withLock {
        if (tokenHolder.snapshot() != expectedSession) return@withLock false

        sessionStore.clear()
        tokenHolder.clear()
        true
    }

    private suspend fun persistLocked(session: AuthSession) {
        sessionStore.save(session)
        updateTokenHolder(session)
    }

    private fun updateTokenHolder(session: AuthSession) {
        tokenHolder.update(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.self.userId,
        )
    }

    private fun SessionTokenHolder.SessionSnapshot.matches(session: AuthSession?): Boolean {
        return if (session == null) {
            accessToken == null && refreshToken == null && userId == null
        } else {
            accessToken == session.accessToken &&
                refreshToken == session.refreshToken &&
                userId == session.self.userId
        }
    }
}
