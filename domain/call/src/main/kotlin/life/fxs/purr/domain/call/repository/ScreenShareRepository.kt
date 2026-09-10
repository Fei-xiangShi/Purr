package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.ScreenShareSession
import life.fxs.purr.domain.call.model.ScreenShareSnapshot
import life.fxs.purr.domain.call.model.ScreenShareSource

interface ScreenShareRepository {
    fun observe(callId: String): Flow<ScreenShareSnapshot>
    suspend fun create(callId: String, source: ScreenShareSource): AppResult<ScreenShareSession>
    suspend fun refresh(callId: String): AppResult<ScreenShareSession?>
    suspend fun stop(callId: String): AppResult<ScreenShareSession?>
}
