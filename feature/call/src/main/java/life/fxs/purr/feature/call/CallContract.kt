package life.fxs.purr.feature.call

import android.content.Intent
import life.fxs.purr.core.media.screenshare.ScreenSharePublishRequest
import life.fxs.purr.core.media.screenshare.ScreenShareQuality
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.LocalScreenShareState
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.model.ScreenSharePublishing
import life.fxs.purr.domain.call.model.ScreenShareSession

enum class CallScreenState {
    Idle,
    Dialing,
    Connecting,
    Waiting,
    Active,
    SystemCallSuspended,
    ResumingAfterSystemCall,
    RemoteSystemCallSuspended,
    RemoteResumingAfterSystemCall,
    Reconnecting,
    Ending,
    Ended,
    Failed,
}

sealed interface CallIntent {
    data class StartNewOutgoingCall(
        val pairId: String,
        val remoteDisplayName: String = "Purr",
    ) : CallIntent
    data class OpenExistingCall(
        val pairId: String,
        val callId: String,
        val remoteDisplayName: String = "Purr",
        val direction: CallDirection,
    ) : CallIntent
    data class MicrophonePermissionResult(
        val granted: Boolean,
        val permanentlyDenied: Boolean = false,
    ) : CallIntent
    data object MuteToggle : CallIntent
    data class RouteSelect(val route: AudioRoute) : CallIntent
    data object OpenScreenSharePicker : CallIntent
    data object DismissScreenSharePicker : CallIntent
    data class SelectPublishQuality(val quality: ScreenShareQuality) : CallIntent
    data object StartMobileScreenShare : CallIntent
    data object StartObsScreenShare : CallIntent
    data class ScreenCapturePermissionResult(
        val resultCode: Int,
        val data: Intent?,
    ) : CallIntent
    data object RetryRemoteScreenShare : CallIntent
    data object StopScreenShare : CallIntent
    data object DismissObsSetup : CallIntent
    data object EndCall : CallIntent
}

enum class RemoteScreenShareUiState {
    None,
    Preparing,
    Connecting,
    Buffering,
    Live,
    Failed,
    Stopped,
}

data class CallScreenShareState(
    val session: ScreenShareSession? = null,
    val isOwnedByCurrentUser: Boolean = false,
    val localState: LocalScreenShareState = LocalScreenShareState.Idle,
    val remoteState: RemoteScreenShareUiState = RemoteScreenShareUiState.None,
    val errorMessage: String? = null,
    val sourcePickerVisible: Boolean = false,
    val obsSetupVisible: Boolean = false,
    val obsPublishing: ScreenSharePublishing? = null,
    val isCreating: Boolean = false,
    val remoteAspectRatio: Float = 16f / 9f,
    val publishQuality: ScreenShareQuality = ScreenShareQuality.FULL_HD60,
)

data class CallState(
    val screenState: CallScreenState = CallScreenState.Idle,
    val session: CallSession? = null,
    val localAudioState: LocalAudioState = LocalAudioState.Disabled,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.Earpiece),
    val activeRoute: AudioRoute = AudioRoute.Earpiece,
    val recordingState: RecordingState = RecordingState.NotRecording,
    val isForegroundServiceActive: Boolean = false,
    val isLoading: Boolean = false,
    val failureMessage: String? = null,
    val screenShare: CallScreenShareState = CallScreenShareState(),
)

sealed interface CallEffect {
    data object RequestMicrophonePermission : CallEffect
    data object OpenAppSettings : CallEffect
    data object NavigateHome : CallEffect
    data class RequestScreenCapturePermission(
        val request: ScreenSharePublishRequest,
    ) : CallEffect
    data class ShowMessage(val message: String) : CallEffect
}
