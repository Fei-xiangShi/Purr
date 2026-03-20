package life.fxs.purr.domain.call.model

data class CallSession(
    val callId: String,
    val pairId: String,
    val participantIdentity: ParticipantIdentity,
    val roomName: String,
    val wsUrl: String,
    val token: String,
    val connectionState: CallConnectionState = CallConnectionState.Idle,
    val localAudioState: LocalAudioState = LocalAudioState.Disabled,
    val recordingState: RecordingState = RecordingState.NotRecording,
    val uiSnapshot: CallUiSnapshot = CallUiSnapshot(),
)
