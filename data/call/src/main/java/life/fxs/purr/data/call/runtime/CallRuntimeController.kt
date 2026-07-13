package life.fxs.purr.data.call.runtime

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.model.AudioRoute

/** Owns the process-local media runtime and its Android foreground lifetime. */
interface CallRuntimeController {
    /** Raw media facts. Domain mapping belongs to the data coordinator/reducer. */
    val mediaEvents: Flow<MediaCallEvent>

    suspend fun execute(command: MediaCallCommand)

    suspend fun releaseResources()

    suspend fun selectAudioRoute(route: AudioRoute)
}

data class CallMediaConnection(
    val wsUrl: String,
    val accessToken: String,
)
