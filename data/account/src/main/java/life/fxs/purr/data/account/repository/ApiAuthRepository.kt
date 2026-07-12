package life.fxs.purr.data.account.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.asAppError
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.core.network.model.LoginRequestDto
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.data.account.network.SessionTokenHolder
import life.fxs.purr.data.account.network.SessionWriter
import life.fxs.purr.data.account.network.toDomain
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.repository.AuthRepository

@Singleton
class ApiAuthRepository @Inject constructor(
    private val authApi: PurrAuthApi,
    private val sessionStore: SessionStore,
    private val sessionWriter: SessionWriter,
    private val sessionTokenHolder: SessionTokenHolder,
) : AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionState = sessionStore.session
        .onEach { session ->
            sessionTokenHolder.update(
                accessToken = session?.accessToken,
                refreshToken = session?.refreshToken,
                userId = session?.self?.userId,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun observeSession(): Flow<AuthSession?> = sessionState

    override fun currentSession(): AuthSession? = sessionState.value

    override suspend fun login(username: String, password: String): AppResult<AuthSession> {
        return try {
            val session = authApi.login(
                LoginRequestDto(
                    username = username,
                    password = password,
                ),
            ).toDomain()
            sessionWriter.persist(session)
            AppResult.Success(session)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppResult.Failure(throwable.asAppError())
        }
    }

    override suspend fun logout(): AppResult<Unit> {
        val accessToken = sessionTokenHolder.accessToken()
        return try {
            if (!accessToken.isNullOrBlank()) {
                authApi.logout("Bearer $accessToken")
            }
            sessionWriter.clear()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            sessionWriter.clear()
            AppResult.Success(Unit)
        }
    }
}
