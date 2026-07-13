package life.fxs.purr.data.call.mapper

import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.domain.call.model.CallTiming
import life.fxs.purr.domain.call.model.RecordingState

internal fun CallStatusDto.toCallTiming(
    previous: CallTiming = CallTiming(),
    synchronizedAtMonotonicMillis: Long = System.nanoTime() / 1_000_000L,
    @Suppress("UNUSED_PARAMETER") clientNowEpochMillis: Long = System.currentTimeMillis(),
): CallTiming {
    val normalizedState = state.lowercase()
    if (normalizedState != "active" && normalizedState != "ended") return CallTiming()

    val startedAt = startedAtEpochMillis ?: return CallTiming()
    val endedAt = endedAtEpochMillis.takeIf { normalizedState == "ended" }
    val synchronizedDuration = durationMillis
        ?: endedAt?.minus(startedAt)
        ?: serverNowEpochMillis?.minus(startedAt)
        ?: previous.takeIf {
            it.isRunning && it.startedAtEpochMillis == startedAt
        }?.durationAtMonotonicMillis(synchronizedAtMonotonicMillis)
        ?: 0L

    return CallTiming(
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = endedAt,
        synchronizedDurationMillis = synchronizedDuration.coerceAtLeast(0L),
        synchronizedAtMonotonicMillis = synchronizedAtMonotonicMillis,
        isRunning = normalizedState == "active" && endedAt == null,
    )
}

internal fun String.toRecordingState(): RecordingState = when (lowercase()) {
    "idle",
    "stopped",
    -> RecordingState.NotRecording
    "starting" -> RecordingState.Starting
    "recording" -> RecordingState.Recording
    "stopping" -> RecordingState.Stopping
    "failed" -> RecordingState.Failed()
    else -> RecordingState.Failed("Unknown recording status: $this")
}
