package life.fxs.purr.domain.account.repository

import life.fxs.purr.core.common.AppResult

interface AccountSecurityRepository {
    suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<Unit>
}
