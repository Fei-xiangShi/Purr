package life.fxs.purr.core.media.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

interface CallAudioFocusManager {
    suspend fun requestFocus(): Boolean
    suspend fun abandonFocus()
}

class AndroidCallAudioFocusManager(
    private val audioManager: AudioManager,
) : CallAudioFocusManager {
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { }
        .build()

    override suspend fun requestFocus(): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override suspend fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
