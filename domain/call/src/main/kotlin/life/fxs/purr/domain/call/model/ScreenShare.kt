package life.fxs.purr.domain.call.model

enum class ScreenShareSource(val wireValue: String) {
    Mobile("mobile"),
    Obs("obs"),
}

enum class ScreenShareStatus(val wireValue: String) {
    Authorized("authorized"),
    Live("live"),
    Stopping("stopping"),
    Stopped("stopped"),
    Expired("expired"),
    Failed("failed"),
}

data class ScreenShareMediaEndpoint(
    val url: String,
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
)

data class ScreenShareSrtSettings(
    val url: String,
    val streamId: String,
    val passphrase: String,
)

data class ScreenSharePublishing(
    val whip: ScreenShareMediaEndpoint,
    val srt: ScreenShareSrtSettings? = null,
)

data class ScreenShareSession(
    val shareId: String,
    val callId: String,
    val ownerUserId: String,
    val source: ScreenShareSource,
    val status: ScreenShareStatus,
    val mediaPath: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val liveAtEpochMillis: Long? = null,
    val stoppedAtEpochMillis: Long? = null,
    val publishing: ScreenSharePublishing? = null,
    val playback: ScreenShareMediaEndpoint? = null,
    val errorMessage: String? = null,
)

sealed interface LocalScreenShareState {
    data object Idle : LocalScreenShareState
    data class RequestingPermission(val shareId: String) : LocalScreenShareState
    data class Connecting(val shareId: String) : LocalScreenShareState
    data class Live(val shareId: String) : LocalScreenShareState
    data class Stopping(val shareId: String) : LocalScreenShareState
    data class Failed(val shareId: String?, val message: String) : LocalScreenShareState
}

data class ScreenShareSnapshot(
    val callId: String,
    val session: ScreenShareSession? = null,
    val isOwnedByCurrentUser: Boolean = false,
    val localState: LocalScreenShareState = LocalScreenShareState.Idle,
    val syncErrorMessage: String? = null,
)
