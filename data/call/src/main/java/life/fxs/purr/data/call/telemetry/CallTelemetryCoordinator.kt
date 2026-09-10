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
import life.fxs.purr.core.common.PurrLogger
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
    private val logger: PurrLogger,
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
                    logger.d(LOG_TAG, "callId=$callId phase=telemetry event=start intervalMs=$SAMPLE_INTERVAL_MILLIS")
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
                            logger.d(
                                LOG_TAG,
                                "callId=$callId phase=telemetry event=sample " +
                                    "remoteConnected=${collected.remoteConnected} " +
                                    "path=${collected.transport.path} " +
                                    "upKbps=${collected.transport.uplinkBitrateKbps} " +
                                    "downKbps=${collected.transport.downlinkBitrateKbps} " +
                                    "sendCodec=${collected.audio.sendCodec} " +
                                    "receiveCodec=${collected.audio.receiveCodec}",
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            logger.e(
                                LOG_TAG,
                                error,
                                "callId=$callId phase=telemetry event=sample_error",
                            )
                        }
                    }
                    logger.d(LOG_TAG, "callId=$callId phase=telemetry event=stop")
                }
        }
    }

    private companion object {
        const val LOG_TAG = "CallTelemetry"
        const val SAMPLE_INTERVAL_MILLIS = 15_000L
    }
}
