package life.fxs.purr.feature.call

import life.fxs.purr.domain.call.model.CallRecording

data class RecordingLibraryState(
    val recordings: List<CallRecording> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val playbackLoadingRecordingId: String? = null,
    val playingRecordingId: String? = null,
    val errorMessage: String? = null,
)

sealed interface RecordingLibraryIntent {
    data object Refresh : RecordingLibraryIntent
    data object LoadMore : RecordingLibraryIntent
    data class Play(val recordingId: String) : RecordingLibraryIntent
    data class PlaybackStarted(val recordingId: String) : RecordingLibraryIntent
    data class PlaybackStopped(val recordingId: String) : RecordingLibraryIntent
    data class PlaybackFailed(val recordingId: String, val reason: String?) : RecordingLibraryIntent
}

sealed interface RecordingLibraryEffect {
    data class Play(val recordingId: String, val url: String) : RecordingLibraryEffect
    data object Pause : RecordingLibraryEffect
}
