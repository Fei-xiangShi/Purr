package life.fxs.purr.core.media.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CallAudioFocusState {
    None,
    Granted,
    Lost,
    LostTransient,
    Ducking,
}

interface CallAudioFocusManager {
    val state: StateFlow<CallAudioFocusState>

    suspend fun requestFocus(): Boolean
    suspend fun abandonFocus()
}

class AndroidCallAudioFocusManager(
    private val audioManager: AudioManager,
) : CallAudioFocusManager {
    private val focusMutex = Mutex()
    private val _state = MutableStateFlow(CallAudioFocusState.None)
    @Volatile
    private var hasFocus: Boolean = false

    override val state: StateFlow<CallAudioFocusState> = _state.asStateFlow()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        _state.value = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> CallAudioFocusState.Granted
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                CallAudioFocusState.Lost
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> CallAudioFocusState.LostTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> CallAudioFocusState.Ducking
            else -> _state.value
        }
    }

    private val focusRequest: AudioFocusRequest = createFocusRequest()

    override suspend fun requestFocus(): Boolean = focusMutex.withLock {
        if (hasFocus && _state.value == CallAudioFocusState.Granted) {
            return@withLock true
        }
        if (request()) {
            hasFocus = true
            _state.value = CallAudioFocusState.Granted
            return@withLock true
        }
        hasFocus = false
        _state.value = CallAudioFocusState.None
        false
    }

    override suspend fun abandonFocus() = focusMutex.withLock {
        if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        _state.value = CallAudioFocusState.None
    }

    private fun createFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    private fun request(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
}
