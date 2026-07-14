package life.fxs.purr.feature.call

import life.fxs.purr.domain.call.model.CallDetail
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallTranscript

data class CallDetailState(
    val callId: String,
    val detail: CallDetail? = null,
    val recordings: List<CallRecording> = emptyList(),
    val transcript: CallTranscript? = null,
    val downloadingRecordingIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface CallDetailIntent {
    data object Retry : CallDetailIntent
    data class DownloadRecording(val recordingId: String) : CallDetailIntent
}

sealed interface CallDetailEffect {
    data class DownloadReady(val url: String, val fileName: String) : CallDetailEffect
}

data class RecordingDownloadRequest(val url: String, val fileName: String)
