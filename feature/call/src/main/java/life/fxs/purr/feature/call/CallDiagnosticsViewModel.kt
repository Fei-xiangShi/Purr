package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.repository.CallDiagnosticsRepository

@HiltViewModel
class CallDiagnosticsViewModel @Inject constructor(
    repository: CallDiagnosticsRepository,
) : ViewModel() {
    val metrics = repository.observeMetrics().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = CallQualityMetrics(),
    )

    val networkHistory = metrics.runningFold(emptyList<NetworkGraphSample>()) { history, current ->
        val transport = current.transport
        val sampledAtMillis = transport.sampledAtMillis ?: return@runningFold history
        if (history.lastOrNull()?.sampledAtMillis == sampledAtMillis) {
            return@runningFold history
        }
        (history + NetworkGraphSample(
            sampledAtMillis = sampledAtMillis,
            roundTripTimeMs = transport.roundTripTimeMs,
            jitterMs = transport.jitterMs,
            packetLossPercent = listOfNotNull(
                transport.uplinkPacketLossPercent,
                transport.downlinkPacketLossPercent,
            ).maxOrNull(),
            uplinkBitrateKbps = transport.uplinkBitrateKbps,
            downlinkBitrateKbps = transport.downlinkBitrateKbps,
            estimatedUpstreamKbps = current.device.estimatedUpstreamKbps,
            estimatedDownstreamKbps = current.device.estimatedDownstreamKbps,
        )).takeLast(NETWORK_HISTORY_SIZE)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = emptyList(),
    )

    private companion object {
        const val NETWORK_HISTORY_SIZE = 60
    }
}

data class NetworkGraphSample(
    val sampledAtMillis: Long,
    val roundTripTimeMs: Double?,
    val jitterMs: Double?,
    val packetLossPercent: Double?,
    val uplinkBitrateKbps: Double?,
    val downlinkBitrateKbps: Double?,
    val estimatedUpstreamKbps: Double?,
    val estimatedDownstreamKbps: Double?,
)
