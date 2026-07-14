package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.StateFlow

interface CallAudioLevelProvider {
    val localAudioLevel: StateFlow<Float>
    val remoteAudioLevel: StateFlow<Float>
}
