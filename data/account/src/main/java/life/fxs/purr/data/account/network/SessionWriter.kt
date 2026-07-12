package life.fxs.purr.data.account.network

import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.domain.account.model.AuthSession

@Singleton
class SessionWriter @Inject constructor(
    private val sessionStore: SessionStore,
    private val tokenHolder: SessionTokenHolder,
) {
    suspend fun persist(session: AuthSession) {
        sessionStore.save(session)
        tokenHolder.update(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.self.userId,
        )
    }

    suspend fun clear() {
        sessionStore.clear()
        tokenHolder.clear()
    }
}
