package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallHistoryApi
import life.fxs.purr.core.network.model.CallQualitySummaryDto
import life.fxs.purr.domain.call.model.CallDetail
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallOutcome
import life.fxs.purr.domain.call.model.CallQualitySummary
import life.fxs.purr.domain.call.repository.CallDetailRepository

@Singleton
class ApiCallDetailRepository @Inject constructor(
    private val api: PurrCallHistoryApi,
) : CallDetailRepository {
    override suspend fun loadDetail(callId: String): AppResult<CallDetail> = callApiResult {
        api.getDetail(callId).let { detail ->
            CallDetail(
                callId = detail.callId,
                direction = if (detail.direction.equals("incoming", true)) {
                    CallDirection.Incoming
                } else {
                    CallDirection.Outgoing
                },
                outcome = when (detail.outcome.lowercase()) {
                    "missed" -> CallOutcome.Missed
                    "cancelled" -> CallOutcome.Cancelled
                    else -> CallOutcome.Completed
                },
                requestedAtEpochMillis = detail.requestedAtEpochMillis,
                connectedAtEpochMillis = detail.connectedAtEpochMillis,
                endedAtEpochMillis = detail.endedAtEpochMillis,
                ringingDurationMillis = detail.ringingDurationMillis,
                durationMillis = detail.durationMillis,
                recordingStatus = detail.recordingStatus,
                recordingCount = detail.recordingCount,
                recordingAvailable = detail.recordingAvailable,
                quality = detail.quality?.toDomain(),
            )
        }
    }
}

private fun CallQualitySummaryDto.toDomain() = CallQualitySummary(
    sampleCount = sampleCount,
    averageRoundTripTimeMs = averageRoundTripTimeMs,
    averageJitterMs = averageJitterMs,
    averagePacketLossPercent = averagePacketLossPercent,
    maximumPacketLossPercent = maximumPacketLossPercent,
    averageUplinkBitrateKbps = averageUplinkBitrateKbps,
    averageDownlinkBitrateKbps = averageDownlinkBitrateKbps,
    networkTransports = networkTransports,
    codecs = codecs,
)
