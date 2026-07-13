package life.fxs.purr.data.call.repository

import android.os.SystemClock
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
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
import life.fxs.purr.data.call.diagnostics.RemoteAudioLevelSample
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
                observeRemoteAudioLevel(room),
            ) { rtcMetrics, localAudioLevel, remoteAudioLevel ->
                rtcMetrics.copy(
                    remoteConnected = room?.remoteParticipants?.isNotEmpty() == true,
                    audio = rtcMetrics.audio.copy(
                        localLevelPercent = (localAudioLevel * 100f).roundToInt().coerceIn(0, 100),
                        remoteLevelPercent = remoteAudioLevel.levelPercent,
                        localSpeaking = localAudioLevel >= LOCAL_SPEAKING_LEVEL,
                        remoteSpeaking = remoteAudioLevel.speaking,
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

    private fun observeRemoteAudioLevel(room: Room?): Flow<RemoteAudioLevelSample> = flow {
        emit(liveKitCollector.collectRemoteAudioLevel(room))
        if (room == null) return@flow

        // Participant.audioLevel only changes when LiveKit receives a speaker update. Observing
        // those events avoids a high-frequency loop that repeatedly publishes the same SDK value.
        room.events.collect { event ->
            when (event) {
                is RoomEvent.ActiveSpeakersChanged,
                is RoomEvent.ParticipantConnected,
                is RoomEvent.ParticipantDisconnected,
                -> emit(liveKitCollector.collectRemoteAudioLevel(room))
                else -> Unit
            }
        }
    }

    private companion object {
        // High-frequency collection only runs while the diagnostics screen is subscribed.
        const val WEBRTC_STATS_INTERVAL_MILLIS = WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS
        const val LOCAL_SPEAKING_LEVEL = 0.035f
    }
}
