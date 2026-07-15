package life.fxs.purr.core.media.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tracks app media readiness while Telecom exclusively owns focus, mode, and endpoints. */
class TelecomManagedCallAudioSessionController : CallAudioSessionController {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<CallAudioSessionState>(CallAudioSessionState.Released)

    override val state: StateFlow<CallAudioSessionState> = mutableState.asStateFlow()

    override suspend fun activate() = mutex.withLock {
        when (mutableState.value) {
            CallAudioSessionState.Released -> mutableState.value = CallAudioSessionState.Active
            CallAudioSessionState.Active -> Unit
            CallAudioSessionState.Activating,
            CallAudioSessionState.Releasing,
            -> error("Call audio session is already transitioning")
        }
    }

    override suspend fun release() = mutex.withLock {
        mutableState.value = CallAudioSessionState.Released
    }
}
