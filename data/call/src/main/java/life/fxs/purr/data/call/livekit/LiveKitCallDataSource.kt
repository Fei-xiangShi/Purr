package life.fxs.purr.data.call.livekit

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.domain.call.model.CallSession

interface LiveKitCallDataSource {
    val sessionEvents: Flow<CallSession?>

    suspend fun connect(session: CallSession)
    suspend fun disconnect()
    suspend fun setMuted(muted: Boolean)
    fun updateSession(session: CallSession?)
}
