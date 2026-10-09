package life.fxs.purr.data.call.livekit

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.AudioPresets
import io.livekit.android.room.Room
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveKitRoomFactory @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    fun create(): Room = LiveKit.create(
        appContext = appContext,
        options = pinnedRoomOptions(),
        overrides = applicationOwnedAudioOverrides(),
    )
}

internal fun applicationOwnedAudioOverrides(): LiveKitOverrides = LiveKitOverrides(
    audioOptions = AudioOptions(
        audioHandler = NoAudioHandler(),
        disableCommunicationModeWorkaround = true,
    ),
)

/** Pins the verified voice quality configuration so SDK upgrades cannot change it silently. */
internal fun pinnedRoomOptions(): RoomOptions = RoomOptions(
    audioTrackPublishDefaults = AudioTrackPublishDefaults(
        audioBitrate = AudioPresets.MUSIC.maxBitrate,
        dtx = true,
        red = true,
    ),
    audioTrackCaptureDefaults = LocalAudioTrackOptions(
        noiseSuppression = true,
        echoCancellation = true,
        autoGainControl = true,
        highPassFilter = true,
        typingNoiseDetection = true,
    ),
)
