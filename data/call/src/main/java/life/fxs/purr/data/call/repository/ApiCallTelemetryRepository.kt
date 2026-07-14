package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallTelemetryApi
import life.fxs.purr.core.network.model.CallTelemetryRequestDto
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.repository.CallTelemetryRepository

@Singleton
class ApiCallTelemetryRepository @Inject constructor(
    private val api: PurrCallTelemetryApi,
) : CallTelemetryRepository {
    override suspend fun report(
        callId: String,
        sampledAtEpochMillis: Long,
        metrics: CallQualityMetrics,
    ): AppResult<Unit> = callApiResult {
        api.report(
            callId = callId,
            sample = CallTelemetryRequestDto(
                sampledAtEpochMillis = sampledAtEpochMillis,
                roundTripTimeMs = metrics.transport.roundTripTimeMs,
                jitterMs = metrics.transport.jitterMs,
                uplinkPacketLossPercent = metrics.transport.uplinkPacketLossPercent,
                downlinkPacketLossPercent = metrics.transport.downlinkPacketLossPercent,
                uplinkBitrateKbps = metrics.transport.uplinkBitrateKbps,
                downlinkBitrateKbps = metrics.transport.downlinkBitrateKbps,
                networkTransport = metrics.device.networkTransport.name.lowercase(),
                sendCodec = metrics.audio.sendCodec,
                receiveCodec = metrics.audio.receiveCodec,
                networkValidated = metrics.device.networkValidated,
                networkMetered = metrics.device.networkMetered,
            ),
        )
    }
}
