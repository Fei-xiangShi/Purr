package life.fxs.purr.data.account.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.core.network.model.RefreshRequestDto

@Singleton
class RefreshSessionCoordinator @Inject constructor(
    private val authApi: PurrAuthApi,
    private val sessionTokenHolder: SessionTokenHolder,
    private val sessionWriter: SessionWriter,
) {
    private val mutex = Mutex()

    suspend fun refresh(refreshToken: String): String? = mutex.withLock {
        val latestRefreshToken = sessionTokenHolder.refreshToken() ?: return@withLock null
        if (latestRefreshToken != refreshToken) {
            return@withLock sessionTokenHolder.accessToken()
        }

        return@withLock try {
            val session = authApi.refresh(RefreshRequestDto(refreshToken = refreshToken)).toDomain()
            sessionWriter.persist(session)
            session.accessToken
        } catch (_: Throwable) {
            sessionWriter.clear()
            null
        }
    }
}
