package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallLifecycleState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.PrepareCallParams

interface CallRepository {
    fun observeCallSession(): Flow<CallSession?>
    fun observeCallLifecycle(): Flow<CallLifecycleState>
    suspend fun prepareCall(params: PrepareCallParams): AppResult<CallSession>
    suspend fun connectCall(): AppResult<Unit>
    suspend fun disconnectCall(expectedCallId: String? = null): AppResult<Unit>
    suspend fun setMuted(muted: Boolean): AppResult<Unit>
    suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit>
}
