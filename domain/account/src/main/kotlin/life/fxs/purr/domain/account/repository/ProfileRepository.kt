package life.fxs.purr.domain.account.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.SelfProfile

interface ProfileRepository {
    suspend fun uploadAvatar(contentType: String, bytes: ByteArray): AppResult<SelfProfile>

    suspend fun updateDisplayName(displayName: String): AppResult<SelfProfile>
}
