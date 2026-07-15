package life.fxs.purr.core.media.audio

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CallAudioSessionState {
    Released,
    Activating,
    Active,
    Releasing,
}

val CallAudioSessionState.isReady: Boolean
    get() = this == CallAudioSessionState.Active

interface CallAudioSessionController {
    val state: StateFlow<CallAudioSessionState>

    suspend fun activate()

    suspend fun release()
}

class CoordinatedCallAudioSessionController(
    private val modeController: CallAudioModeController,
    private val focusManager: CallAudioFocusManager,
    private val routeController: AudioRouteController,
) : CallAudioSessionController {
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow(CallAudioSessionState.Released)

    override val state: StateFlow<CallAudioSessionState> = mutableState.asStateFlow()

    override suspend fun activate() = lifecycleMutex.withLock {
        when (mutableState.value) {
            CallAudioSessionState.Released -> Unit
            CallAudioSessionState.Active -> return@withLock
            CallAudioSessionState.Activating,
            CallAudioSessionState.Releasing,
            -> error("Call audio session is already transitioning")
        }

        mutableState.value = CallAudioSessionState.Activating
        try {
            modeController.activate()
            check(focusManager.requestFocus()) { "Unable to gain call audio focus" }
            routeController.selectDefaultRoute()
            mutableState.value = CallAudioSessionState.Active
        } catch (error: Throwable) {
            withContext(NonCancellable) { releaseOwnedResources() }?.let(error::addSuppressed)
            mutableState.value = CallAudioSessionState.Released
            throw error
        }
    }

    override suspend fun release() = lifecycleMutex.withLock {
        if (mutableState.value == CallAudioSessionState.Released) return@withLock
        mutableState.value = CallAudioSessionState.Releasing
        val failure = withContext(NonCancellable) { releaseOwnedResources() }
        mutableState.value = CallAudioSessionState.Released
        failure?.let { throw it }
        Unit
    }

    private suspend fun releaseOwnedResources(): Throwable? {
        var failure: Throwable? = null
        failure = captureFailure(failure) { routeController.releaseCallRoute() }
        failure = captureFailure(failure) { focusManager.abandonFocus() }
        failure = captureFailure(failure) { modeController.release() }
        return failure
    }
}

private suspend fun captureFailure(
    current: Throwable?,
    block: suspend () -> Unit,
): Throwable? = try {
    block()
    current
} catch (error: Throwable) {
    current?.also { it.addSuppressed(error) } ?: error
}
