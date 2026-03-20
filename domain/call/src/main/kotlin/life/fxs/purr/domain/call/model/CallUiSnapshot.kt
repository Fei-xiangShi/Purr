package life.fxs.purr.domain.call.model

import life.fxs.purr.core.model.AudioRoute

data class NetworkQuality(
    val uplinkScore: Int? = null,
    val downlinkScore: Int? = null,
    val lastUpdatedEpochMillis: Long? = null,
)

data class CallUiSnapshot(
    val remoteParticipantConnected: Boolean = false,
    val activeAudioRoute: AudioRoute = AudioRoute.Earpiece,
    val availableAudioRoutes: List<AudioRoute> = listOf(AudioRoute.Earpiece),
    val networkQuality: NetworkQuality = NetworkQuality(),
    val isForegroundServiceActive: Boolean = false,
)
