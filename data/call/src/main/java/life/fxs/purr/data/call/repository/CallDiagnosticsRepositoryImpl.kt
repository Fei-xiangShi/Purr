package life.fxs.purr.data.call.repository

import android.os.SystemClock
import io.livekit.android.room.Room
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import life.fxs.purr.data.call.audio.WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS
import life.fxs.purr.data.call.audio.nextSampleAtMillis
import life.fxs.purr.data.call.diagnostics.AndroidCallEnvironmentReader
import life.fxs.purr.data.call.diagnostics.LiveKitCallMetricsCollector
import life.fxs.purr.data.call.diagnostics.RtpByteSample
import life.fxs.purr.data.call.livekit.CallRoomStateProvider
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import life.fxs.purr.domain.call.repository.CallDiagnosticsRepository
import kotlin.math.roundToInt

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CallDiagnosticsRepositoryImpl @Inject constructor(
    private val roomStateProvider: CallRoomStateProvider,
    private val liveKitCollector: LiveKitCallMetricsCollector,
    private val environmentReader: AndroidCallEnvironmentReader,
    private val audioLevelProvider: CallAudioLevelProvider,
) : CallDiagnosticsRepository {
    override fun observeMetrics(): Flow<CallQualityMetrics> = roomStateProvider.room
        .flatMapLatest { room ->
            combine(
                observeRtcMetrics(room),
                audioLevelProvider.localAudioLevel,
                audioLevelProvider.remoteAudioLevel,
            ) { rtcMetrics, localAudioLevel, remoteAudioLevel ->
                rtcMetrics.copy(
                    remoteConnected = room?.remoteParticipants?.isNotEmpty() == true,
                    audio = rtcMetrics.audio.copy(
                        localLevelPercent = (localAudioLevel * 100f).roundToInt().coerceIn(0, 100),
                        remoteLevelPercent = (remoteAudioLevel * 100f).roundToInt().coerceIn(0, 100),
                        localSpeaking = localAudioLevel >= LOCAL_SPEAKING_LEVEL,
                        remoteSpeaking = remoteAudioLevel >= REMOTE_SPEAKING_LEVEL,
                    ),
                )
            }
        }
        .flowOn(Dispatchers.Default)

    private fun observeRtcMetrics(room: Room?): Flow<CallQualityMetrics> = flow {
        var previousSample: RtpByteSample? = null
        var scheduledAtMillis = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            val liveKitMetrics = liveKitCollector.collect(room, previousSample)
            emit(
                CallQualityMetrics(
                    remoteConnected = liveKitMetrics.remoteConnected,
                    audio = liveKitMetrics.audio,
                    transport = liveKitMetrics.transport,
                    device = environmentReader.read(),
                ),
            )
            previousSample = liveKitMetrics.sample
            val nowMillis = SystemClock.elapsedRealtime()
            scheduledAtMillis = nextSampleAtMillis(
                scheduledAtMillis = scheduledAtMillis,
                nowMillis = nowMillis,
                intervalMillis = WEBRTC_STATS_INTERVAL_MILLIS,
            )
            delay((scheduledAtMillis - nowMillis).coerceAtLeast(0L))
        }
    }

    private companion object {
        // High-frequency collection only runs while the diagnostics screen is subscribed.
        const val WEBRTC_STATS_INTERVAL_MILLIS = WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS
        const val LOCAL_SPEAKING_LEVEL = 0.035f
        const val REMOTE_SPEAKING_LEVEL = 0.035f
    }
}
