package life.fxs.purr.data.call.livekit

import io.livekit.android.events.DisconnectReason
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Separates network-class media loss, which is worth a rejoin, from decisions made by a peer or server. */
internal object DisconnectReasonPolicy {
    fun isRecoverable(reason: DisconnectReason?, error: Throwable?): Boolean = when (reason) {
        null,
        DisconnectReason.UNKNOWN_REASON,
        DisconnectReason.SIGNAL_CLOSE,
        DisconnectReason.CONNECTION_TIMEOUT,
        DisconnectReason.MEDIA_FAILURE,
        DisconnectReason.STATE_MISMATCH,
        DisconnectReason.SERVER_SHUTDOWN,
        DisconnectReason.MIGRATION,
        -> true
        DisconnectReason.JOIN_FAILURE -> error.isNetworkError()
        else -> false
    }

    fun isRecoverableConnectFailure(error: Throwable?): Boolean = error.isNetworkError()

    fun code(reason: DisconnectReason?): String = reason?.name?.lowercase() ?: "unknown_reason"

    private fun Throwable?.isNetworkError(): Boolean = this is IOException || this is TimeoutException
}
