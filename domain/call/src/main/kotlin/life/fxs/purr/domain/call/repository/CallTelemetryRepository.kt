package life.fxs.purr.domain.call.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallQualityMetrics

interface CallTelemetryRepository {
    suspend fun report(
        callId: String,
        sampledAtEpochMillis: Long,
        metrics: CallQualityMetrics,
    ): AppResult<Unit>
}
