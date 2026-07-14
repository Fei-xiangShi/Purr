package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class CallQualitySummaryDto(
    val sampleCount: Int,
    val averageRoundTripTimeMs: Double? = null,
    val averageJitterMs: Double? = null,
    val averagePacketLossPercent: Double? = null,
    val maximumPacketLossPercent: Double? = null,
    val averageUplinkBitrateKbps: Double? = null,
    val averageDownlinkBitrateKbps: Double? = null,
    val networkTransports: List<String> = emptyList(),
    val codecs: List<String> = emptyList(),
)

@Serializable
data class CallDetailDto(
    val callId: String,
    val direction: String,
    val outcome: String,
    val requestedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: String,
    val recordingCount: Int,
    val recordingAvailable: Boolean,
    val quality: CallQualitySummaryDto? = null,
)

@Serializable
data class CallTelemetryRequestDto(
    val sampledAtEpochMillis: Long,
    val roundTripTimeMs: Double? = null,
    val jitterMs: Double? = null,
    val uplinkPacketLossPercent: Double? = null,
    val downlinkPacketLossPercent: Double? = null,
    val uplinkBitrateKbps: Double? = null,
    val downlinkBitrateKbps: Double? = null,
    val networkTransport: String? = null,
    val sendCodec: String? = null,
    val receiveCodec: String? = null,
    val networkValidated: Boolean = false,
    val networkMetered: Boolean = false,
)
