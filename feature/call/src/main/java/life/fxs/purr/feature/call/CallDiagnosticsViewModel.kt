package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.repository.CallDiagnosticsRepository
import life.fxs.purr.core.media.screenshare.ScreenShareDiagnosticsStore

@HiltViewModel
class CallDiagnosticsViewModel @Inject constructor(
    repository: CallDiagnosticsRepository,
    diagnostics: ScreenShareDiagnosticsStore = ScreenShareDiagnosticsStore(),
) : ViewModel() {
    val publishing = diagnostics.publishing
    val receiving = diagnostics.receiving
    val metrics = repository.observeMetrics().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = CallQualityMetrics(),
    )

    val networkHistory = metrics.map { it.transport }.distinctUntilChanged()
        .runningFold(emptyList<NetworkGraphSample>()) { history, transport ->
            val sampledAtMillis = transport.sampledAtMillis ?: return@runningFold emptyList()
            if (history.lastOrNull()?.sampledAtMillis == sampledAtMillis) {
                return@runningFold history
            }
            appendNetworkGraphSample(
                history = history,
                sample = NetworkGraphSample(
                    sampledAtMillis = sampledAtMillis,
                    roundTripTimeMs = transport.roundTripTimeMs,
                    jitterMs = transport.jitterMs,
                    uplinkPacketLossPercent = transport.uplinkPacketLossPercent,
                    downlinkPacketLossPercent = transport.downlinkPacketLossPercent,
                    uplinkBitrateKbps = transport.uplinkBitrateKbps,
                    downlinkBitrateKbps = transport.downlinkBitrateKbps,
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
    val uplinkPacketLossPercent: Double?,
    val downlinkPacketLossPercent: Double?,
    val uplinkBitrateKbps: Double?,
    val downlinkBitrateKbps: Double?,
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
