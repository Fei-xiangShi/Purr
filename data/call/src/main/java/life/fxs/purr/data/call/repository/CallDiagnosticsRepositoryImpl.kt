package life.fxs.purr.data.call.repository

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import life.fxs.purr.data.call.diagnostics.AndroidCallEnvironmentReader
import life.fxs.purr.data.call.diagnostics.LiveKitCallMetricsCollector
import life.fxs.purr.data.call.diagnostics.RtpByteSample
import life.fxs.purr.data.call.livekit.CallRoomStateProvider
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.repository.CallDiagnosticsRepository

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CallDiagnosticsRepositoryImpl @Inject constructor(
    private val roomStateProvider: CallRoomStateProvider,
    private val liveKitCollector: LiveKitCallMetricsCollector,
    private val environmentReader: AndroidCallEnvironmentReader,
) : CallDiagnosticsRepository {
    override fun observeMetrics(): Flow<CallQualityMetrics> = roomStateProvider.room
        .flatMapLatest { room ->
            flow {
                var previousSample: RtpByteSample? = null
                var latestMetrics = CallQualityMetrics()
                var lastStatsAtMillis = Long.MIN_VALUE
                while (currentCoroutineContext().isActive) {
                    val now = SystemClock.elapsedRealtime()
                    if (
                        latestMetrics.transport.sampledAtMillis == null ||
                        now - lastStatsAtMillis >= WEBRTC_STATS_INTERVAL_MILLIS
                    ) {
                        val liveKitMetrics = liveKitCollector.collect(room, previousSample)
                        latestMetrics = CallQualityMetrics(
                            remoteConnected = liveKitMetrics.remoteConnected,
                            audio = liveKitMetrics.audio,
                            transport = liveKitMetrics.transport,
                            device = environmentReader.read(),
                        )
                        previousSample = liveKitMetrics.sample
                        lastStatsAtMillis = now
                    }
                    val audioLevels = liveKitCollector.collectAudioLevels(room)
                    latestMetrics = latestMetrics.copy(
                        remoteConnected = room?.remoteParticipants?.isNotEmpty() == true,
                        audio = latestMetrics.audio.copy(
                            localLevelPercent = audioLevels.localLevelPercent,
                            remoteLevelPercent = audioLevels.remoteLevelPercent,
                            localSpeaking = audioLevels.localSpeaking,
                            remoteSpeaking = audioLevels.remoteSpeaking,
                        ),
                    )
                    emit(latestMetrics)
                    delay(AUDIO_LEVEL_INTERVAL_MILLIS)
                }
            }
        }
        .flowOn(Dispatchers.Default)

    private companion object {
        // High-frequency collection only runs while the diagnostics screen is subscribed.
        const val AUDIO_LEVEL_INTERVAL_MILLIS = 20L
        const val WEBRTC_STATS_INTERVAL_MILLIS = 50L
    }
}
