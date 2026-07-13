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
        appendNetworkGraphSample(
            history = history,
            sample = NetworkGraphSample(
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
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = emptyList(),
    )
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

internal fun appendNetworkGraphSample(
    history: List<NetworkGraphSample>,
    sample: NetworkGraphSample,
): List<NetworkGraphSample> {
    val latestTimestamp = history.lastOrNull()?.sampledAtMillis
    if (latestTimestamp == sample.sampledAtMillis) return history
    if (latestTimestamp != null && sample.sampledAtMillis < latestTimestamp) return listOf(sample)

    val earliestVisibleTimestamp = (sample.sampledAtMillis - NETWORK_CHART_WINDOW_MILLIS)
        .coerceAtLeast(0L)
    return buildList {
        history.forEach { existing ->
            if (existing.sampledAtMillis >= earliestVisibleTimestamp) add(existing)
        }
        add(sample)
    }.takeLast(NETWORK_CHART_SAMPLE_CAPACITY)
}
