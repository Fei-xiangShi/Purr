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

    suspend fun requestFocus(profile: CallAudioProfile): Boolean
    suspend fun abandonFocus()
}

class AndroidCallAudioFocusManager(
    private val audioManager: AudioManager,
) : CallAudioFocusManager {
    private val focusMutex = Mutex()
    private val _state = MutableStateFlow(CallAudioFocusState.None)
    @Volatile
    private var activeProfile: CallAudioProfile? = null

    override val state: StateFlow<CallAudioFocusState> = _state.asStateFlow()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        _state.value = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> CallAudioFocusState.Granted
            AudioManager.AUDIOFOCUS_LOSS -> {
                activeProfile = null
                CallAudioFocusState.Lost
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> CallAudioFocusState.LostTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> CallAudioFocusState.Ducking
            else -> _state.value
        }
    }

    private val communicationFocusRequest: AudioFocusRequest = createFocusRequest(
        usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
    )
    private val listenOnlyFocusRequest: AudioFocusRequest = createFocusRequest(
        usage = AudioAttributes.USAGE_MEDIA,
    )

    override suspend fun requestFocus(profile: CallAudioProfile): Boolean = focusMutex.withLock {
        if (activeProfile == profile && _state.value == CallAudioFocusState.Granted) {
            return@withLock true
        }
        val previousProfile = activeProfile
        previousProfile?.let { abandonRequest(it) }

        if (request(profile)) {
            activeProfile = profile
            _state.value = CallAudioFocusState.Granted
            return@withLock true
        }

        activeProfile = previousProfile?.takeIf(::request)
        _state.value = if (activeProfile == null) CallAudioFocusState.None else CallAudioFocusState.Granted
        false
    }

    override suspend fun abandonFocus() = focusMutex.withLock {
        activeProfile?.let(::abandonRequest)
        activeProfile = null
        _state.value = CallAudioFocusState.None
    }

    private fun createFocusRequest(usage: Int): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    private fun request(profile: CallAudioProfile): Boolean =
        audioManager.requestAudioFocus(requestFor(profile)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonRequest(profile: CallAudioProfile) {
        audioManager.abandonAudioFocusRequest(requestFor(profile))
    }

    private fun requestFor(profile: CallAudioProfile): AudioFocusRequest = when (profile) {
        CallAudioProfile.Conversational -> communicationFocusRequest
        CallAudioProfile.ListenOnly -> listenOnlyFocusRequest
    }
}
