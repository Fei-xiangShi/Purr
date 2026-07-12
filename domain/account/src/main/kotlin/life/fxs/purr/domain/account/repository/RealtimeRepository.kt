package life.fxs.purr.domain.account.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.model.RealtimeState

interface RealtimeRepository {
    fun observeState(): Flow<RealtimeState>
    fun start()
    fun stop()
    suspend fun refreshActiveCall(): AppResult<Unit>
    suspend fun declineIncomingCall(callId: String): AppResult<Unit>
    fun clearIncomingCall(callId: String)
}
