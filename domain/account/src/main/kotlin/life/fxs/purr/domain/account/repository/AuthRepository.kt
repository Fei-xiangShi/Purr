package life.fxs.purr.domain.account.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.model.AuthSession

interface AuthRepository {
    fun observeSession(): Flow<AuthSession?>
    fun currentSession(): AuthSession?
    suspend fun login(username: String, password: String): AppResult<AuthSession>
    suspend fun logout(): AppResult<Unit>
}
