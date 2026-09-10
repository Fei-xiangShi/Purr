package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult
import life.fxs.purr.domain.call.model.CallLifecycleState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallPreparationRequest

interface CallRepository {
    fun observeCallSession(): Flow<CallSession?>
    fun observeCallLifecycle(): Flow<CallLifecycleState>
    suspend fun prepareCall(request: CallPreparationRequest): AppResult<CallSession>
    suspend fun cancelCallPreparation()
    suspend fun connectCall(): AppResult<Unit>
    suspend fun disconnectCall(callId: String): AppResult<Unit>
    suspend fun suspendForSystemCall(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult
    suspend fun resumeAfterSystemCall(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult
    suspend fun setMuted(muted: Boolean): AppResult<Unit>
    suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit>
}
