package life.fxs.purr.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import retrofit2.HttpException
import io.sentry.SpanStatus

internal fun interruptionSpanStatus(result: String): SpanStatus = when (result) {
    "applied", "degraded" -> SpanStatus.OK
    "cancelled", "superseded" -> SpanStatus.CANCELLED
    else -> SpanStatus.INTERNAL_ERROR
}

/** Capture at the final owner boundary; intermediate attempts remain breadcrumbs. */
internal fun shouldReportHandledError(tag: String, throwable: Throwable?, message: String): Boolean {
    if (throwable is CancellationException && throwable !is TimeoutCancellationException) return false
    if (throwable is SecurityException) return false
    if (throwable is HttpException && throwable.code() in 400..499) return false
    val phase = Regex("\\bphase=([^\\s]+)").find(message)?.groupValues?.get(1)
    val event = Regex("\\bevent=([^\\s]+)").find(message)?.groupValues?.get(1)
    return when (tag) {
        "CallTelemetry" -> false
        "CallRuntime" -> phase == "runtime.connect" && event == "error" && throwable !is TimeoutCancellationException
        "CallLiveKit" -> (phase in setOf("livekit.disconnect", "livekit.release") && event == "error") ||
            (phase == "livekit.interruption" && event == "new_track_cutoff_error")
        "CallLifecycle" -> (phase in setOf("connect", "termination") && event == "timeout") ||
            (phase == "local.release" && event == "error") ||
            (phase == "prepare" && event == "error")
        else -> true
    }
}
