package life.fxs.purr.data.account.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.core.network.model.ChangePasswordRequestDto
import life.fxs.purr.data.account.network.SessionWriter
import life.fxs.purr.domain.account.repository.AccountSecurityRepository

@Singleton
class ApiAccountSecurityRepository @Inject constructor(
    private val accountApi: PurrAccountApi,
    private val sessionWriter: SessionWriter,
) : AccountSecurityRepository {
    override suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<Unit> {
        return try {
            accountApi.changePassword(
                ChangePasswordRequestDto(
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                ),
            )
            sessionWriter.clear()
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppResult.Failure(throwable.asAppError())
        }
    }
}
