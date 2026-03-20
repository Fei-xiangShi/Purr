package life.fxs.purr.domain.call.model

sealed interface LocalAudioState {
    data object Disabled : LocalAudioState
    data object Enabling : LocalAudioState
    data object Enabled : LocalAudioState
    data object Muted : LocalAudioState
    data class Error(val reason: String? = null) : LocalAudioState
}
