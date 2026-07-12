package life.fxs.purr.domain.call.model

data class CallQualityMetrics(
    val remoteConnected: Boolean = false,
    val audio: AudioQualityMetrics = AudioQualityMetrics(),
    val transport: TransportQualityMetrics = TransportQualityMetrics(),
    val device: DeviceCallMetrics = DeviceCallMetrics(),
)

data class AudioQualityMetrics(
    val localLevelPercent: Int = 0,
    val remoteLevelPercent: Int = 0,
    val localSpeaking: Boolean = false,
    val remoteSpeaking: Boolean = false,
    val sendCodec: String? = null,
    val receiveCodec: String? = null,
)

data class TransportQualityMetrics(
    val sampledAtMillis: Long? = null,
    val roundTripTimeMs: Double? = null,
    val uplinkBitrateKbps: Double? = null,
    val downlinkBitrateKbps: Double? = null,
    val uplinkPacketLossPercent: Double? = null,
    val downlinkPacketLossPercent: Double? = null,
    val jitterMs: Double? = null,
    val availableOutgoingKbps: Double? = null,
    val availableIncomingKbps: Double? = null,
    val path: String? = null,
)

data class DeviceCallMetrics(
    val networkTransport: NetworkTransport = NetworkTransport.None,
    val networkValidated: Boolean = false,
    val networkMetered: Boolean = false,
    val estimatedUpstreamKbps: Double? = null,
    val estimatedDownstreamKbps: Double? = null,
    val callVolumePercent: Int = 0,
)

enum class NetworkTransport {
    Wifi,
    Cellular,
    Ethernet,
    Vpn,
    Bluetooth,
    Other,
    None,
}
