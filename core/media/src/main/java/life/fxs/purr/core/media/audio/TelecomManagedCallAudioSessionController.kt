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
        when (val current = mutableState.value) {
            CallAudioSessionState.Released -> {
                mutableState.value = CallAudioSessionState.Active(CallAudioProfile.Conversational)
            }
            is CallAudioSessionState.Active -> check(current.profile == CallAudioProfile.Conversational)
            else -> error("Call audio session cannot activate from $current")
        }
    }

    override suspend fun transitionTo(profile: CallAudioProfile): CallAudioTransitionOutcome =
        mutex.withLock {
            val current = mutableState.value.currentProfile
                ?: return@withLock CallAudioTransitionOutcome.Failed(
                    IllegalStateException("Call audio session is not active"),
                )
            if (profile == CallAudioProfile.ListenOnly) {
                return@withLock CallAudioTransitionOutcome.NotApplicable
            }
            mutableState.value = CallAudioSessionState.Active(current)
            CallAudioTransitionOutcome.AlreadyActive
        }

    override suspend fun release() = mutex.withLock {
        mutableState.value = CallAudioSessionState.Released
    }
}
