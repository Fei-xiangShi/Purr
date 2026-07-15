package life.fxs.purr.core.media.audio

import android.media.AudioManager

interface CallAudioModeController {
    suspend fun activate()

    suspend fun release()
}

class AndroidCallAudioModeController(
    private val audioManager: AudioManager,
) : CallAudioModeController {
    override suspend fun activate() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    override suspend fun release() {
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
