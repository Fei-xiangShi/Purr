package life.fxs.purr.domain.call.model

data class CallDetail(
    val callId: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val requestedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: String,
    val recordingCount: Int,
    val recordingAvailable: Boolean,
    val quality: CallQualitySummary?,
)

data class CallQualitySummary(
    val sampleCount: Int,
    val averageRoundTripTimeMs: Double?,
    val averageJitterMs: Double?,
    val averagePacketLossPercent: Double?,
    val maximumPacketLossPercent: Double?,
    val averageUplinkBitrateKbps: Double?,
    val averageDownlinkBitrateKbps: Double?,
    val networkTransports: List<String>,
    val codecs: List<String>,
)
