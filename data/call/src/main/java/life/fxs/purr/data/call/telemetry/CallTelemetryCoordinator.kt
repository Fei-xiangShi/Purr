package life.fxs.purr.data.call.telemetry

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.data.call.diagnostics.AndroidCallEnvironmentReader
import life.fxs.purr.data.call.diagnostics.LiveKitCallMetricsCollector
import life.fxs.purr.data.call.diagnostics.RtpByteSample
import life.fxs.purr.data.call.livekit.CallRoomStateProvider
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.repository.CallTelemetryRepository

/** Collects a bounded, low-frequency diagnostic stream independently of the diagnostics UI. */
@Singleton
class CallTelemetryCoordinator @Inject constructor(
    private val callRepository: CallRepository,
    private val roomStateProvider: CallRoomStateProvider,
    private val metricsCollector: LiveKitCallMetricsCollector,
    private val environmentReader: AndroidCallEnvironmentReader,
    private val telemetryRepository: CallTelemetryRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            callRepository.observeCallSession()
                .map { session ->
                    session?.callId?.takeIf { session.connectionState == CallConnectionState.Connected }
                }
                .distinctUntilChanged()
                .collectLatest { callId ->
                    callId ?: return@collectLatest
                    var previous: RtpByteSample? = null
                    while (currentCoroutineContext().isActive) {
                        delay(SAMPLE_INTERVAL_MILLIS)
                        try {
                            val collected = metricsCollector.collect(roomStateProvider.room.value, previous)
                            previous = collected.sample
                            telemetryRepository.report(
                                callId = callId,
                                sampledAtEpochMillis = System.currentTimeMillis(),
                                metrics = CallQualityMetrics(
                                    remoteConnected = collected.remoteConnected,
                                    audio = collected.audio,
                                    transport = collected.transport,
                                    device = environmentReader.read(),
                                ),
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            // The next bounded sample remains independently reportable.
                        }
                    }
                }
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 15_000L
    }
}
