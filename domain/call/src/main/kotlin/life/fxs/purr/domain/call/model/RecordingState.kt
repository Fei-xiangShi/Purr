package life.fxs.purr.domain.call.model

sealed interface RecordingState {
    data object NotRecording : RecordingState
    data object Starting : RecordingState
    data object Recording : RecordingState
    data object Stopping : RecordingState
    data class Failed(val reason: String? = null) : RecordingState
}
