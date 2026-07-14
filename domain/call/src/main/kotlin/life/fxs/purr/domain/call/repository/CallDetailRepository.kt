package life.fxs.purr.domain.call.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallDetail

interface CallDetailRepository {
    suspend fun loadDetail(callId: String): AppResult<CallDetail>
}
