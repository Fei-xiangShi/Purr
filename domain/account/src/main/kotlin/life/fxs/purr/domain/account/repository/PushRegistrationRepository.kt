package life.fxs.purr.domain.account.repository

import life.fxs.purr.core.common.AppResult

interface PushRegistrationRepository {
    suspend fun register(installationId: String, token: String): AppResult<Unit>

    suspend fun unregister(installationId: String): AppResult<Unit>
}
