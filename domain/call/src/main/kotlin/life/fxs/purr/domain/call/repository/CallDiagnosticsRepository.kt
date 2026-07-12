package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.domain.call.model.CallQualityMetrics

interface CallDiagnosticsRepository {
    fun observeMetrics(): Flow<CallQualityMetrics>
}
