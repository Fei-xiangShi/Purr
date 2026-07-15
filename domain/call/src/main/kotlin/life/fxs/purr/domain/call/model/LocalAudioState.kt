package life.fxs.purr.domain.call.model

sealed interface LocalAudioState {
    data object Disabled : LocalAudioState
    data object Enabling : LocalAudioState
    data object Enabled : LocalAudioState
    data object Muting : LocalAudioState
    data object Muted : LocalAudioState
    data object Unmuting : LocalAudioState
    data class Error(val reason: String? = null) : LocalAudioState
}

val LocalAudioState.isEffectivelyMuted: Boolean
    get() = this == LocalAudioState.Muted || this == LocalAudioState.Unmuting

val LocalAudioState.canToggleMute: Boolean
    get() = this == LocalAudioState.Enabled || this == LocalAudioState.Muted
