package life.fxs.purr.data.call.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider

@Singleton
class MutableCallAudioLevelProvider @Inject constructor() : CallAudioLevelProvider {
    private val _localAudioLevel = MutableStateFlow(0f)

    override val localAudioLevel: StateFlow<Float> = _localAudioLevel.asStateFlow()

    fun update(level: Float) {
        _localAudioLevel.value = level.coerceIn(0f, 1f)
    }
}
