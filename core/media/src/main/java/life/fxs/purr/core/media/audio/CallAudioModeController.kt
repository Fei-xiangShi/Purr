package life.fxs.purr.core.media.audio

import android.media.AudioManager

interface CallAudioModeController {
    suspend fun apply(profile: CallAudioProfile)

    suspend fun release()
}

class AndroidCallAudioModeController(
    private val audioManager: AudioManager,
) : CallAudioModeController {
    override suspend fun apply(profile: CallAudioProfile) {
        audioManager.mode = when (profile) {
            CallAudioProfile.Conversational -> AudioManager.MODE_IN_COMMUNICATION
            CallAudioProfile.ListenOnly -> AudioManager.MODE_NORMAL
        }
    }

    override suspend fun release() {
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
