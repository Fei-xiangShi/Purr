package life.fxs.purr.domain.call.model

data class CallSession(
    val callId: String,
    val pairId: String,
    val participantIdentity: ParticipantIdentity,
    val roomName: String,
    val connectionState: CallConnectionState = CallConnectionState.Idle,
    val localAudioState: LocalAudioState = LocalAudioState.Disabled,
    val recordingState: RecordingState = RecordingState.NotRecording,
    val timing: CallTiming = CallTiming(),
    val uiSnapshot: CallUiSnapshot = CallUiSnapshot(),
)
