package life.fxs.purr.feature.call

import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.model.CallRecording

enum class CallScreenState {
    Idle,
    Dialing,
    Connecting,
    Waiting,
    Active,
    Reconnecting,
    Ending,
    Ended,
}

sealed interface CallIntent {
    data class ConnectCall(val pairId: String) : CallIntent
    data class MicrophonePermissionResult(
        val granted: Boolean,
        val permanentlyDenied: Boolean = false,
    ) : CallIntent
    data object MuteToggle : CallIntent
    data class RouteSelect(val route: AudioRoute) : CallIntent
    data object EndCall : CallIntent
    data object RefreshRecordings : CallIntent
    data class PlayRecording(val recordingId: String) : CallIntent
    data class RecordingPlaybackStarted(val recordingId: String) : CallIntent
    data class RecordingPlaybackStopped(val recordingId: String) : CallIntent
    data class RecordingPlaybackFailed(val recordingId: String, val reason: String?) : CallIntent
}

data class CallState(
    val screenState: CallScreenState = CallScreenState.Idle,
    val session: CallSession? = null,
    val localAudioState: LocalAudioState = LocalAudioState.Disabled,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.Earpiece),
    val activeRoute: AudioRoute = AudioRoute.Earpiece,
    val recordingState: RecordingState = RecordingState.NotRecording,
    val isForegroundServiceActive: Boolean = false,
    val isLoading: Boolean = false,
    val recordings: List<CallRecording> = emptyList(),
    val isRecordingsLoading: Boolean = false,
    val playbackLoadingRecordingId: String? = null,
    val playingRecordingId: String? = null,
    val recordingsError: String? = null,
)

sealed interface CallEffect {
    data object RequestMicrophonePermission : CallEffect
    data object OpenAppSettings : CallEffect
    data class ShowMessage(val message: String) : CallEffect
    data class PlayRecording(val recordingId: String, val url: String) : CallEffect
    data object PauseRecording : CallEffect
}
