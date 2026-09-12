package life.fxs.purr.core.media.screenshare

enum class ScreenShareFailureCode(val reportable: Boolean) {
    UnsupportedCodec(false),
    PermissionEnded(false),
    CredentialsExpired(false),
    StreamEnded(false),
    Signaling(true),
    IceConnection(true),
    FirstFrameTimeout(true),
    ConnectTimeout(true),
    PublishRetriesExhausted(true),
    EncoderStart(true),
    ServiceStart(true),
    Playback(true),
    PlaybackStalled(true),
    Cleanup(true),
}

class ScreenShareFailureException(
    val code: ScreenShareFailureCode,
    message: String,
) : IllegalStateException(message)

fun interface ScreenShareFailureReporter {
    /** Bounded metadata only. Never accept media URLs, SDP or a provider's raw error text. */
    fun report(callId: String?, shareId: String?, code: ScreenShareFailureCode)
}

object NoOpScreenShareFailureReporter : ScreenShareFailureReporter {
    override fun report(callId: String?, shareId: String?, code: ScreenShareFailureCode) = Unit
}
