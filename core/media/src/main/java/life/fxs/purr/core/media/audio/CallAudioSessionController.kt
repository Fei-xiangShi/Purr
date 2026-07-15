package life.fxs.purr.core.media.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import life.fxs.purr.core.model.AudioRoute

enum class CallAudioProfile {
    Conversational,
    ListenOnly,
}

sealed interface CallAudioSessionState {
    data object Released : CallAudioSessionState

    data class Active(
        val profile: CallAudioProfile,
    ) : CallAudioSessionState

    data class Transitioning(
        val from: CallAudioProfile?,
        val target: CallAudioProfile?,
    ) : CallAudioSessionState

    data class Degraded(
        val profile: CallAudioProfile,
        val failedTarget: CallAudioProfile,
        val reason: String?,
    ) : CallAudioSessionState

    data class Failed(
        val lastKnownProfile: CallAudioProfile?,
        val failedTarget: CallAudioProfile?,
        val reason: String?,
    ) : CallAudioSessionState
}

val CallAudioSessionState.currentProfile: CallAudioProfile?
    get() = when (this) {
        is CallAudioSessionState.Active -> profile
        is CallAudioSessionState.Degraded -> profile
        is CallAudioSessionState.Failed -> lastKnownProfile
        is CallAudioSessionState.Transitioning -> from
        CallAudioSessionState.Released -> null
    }

val CallAudioSessionState.isConversationalReady: Boolean
    get() = when (this) {
        is CallAudioSessionState.Active -> profile == CallAudioProfile.Conversational
        is CallAudioSessionState.Degraded -> profile == CallAudioProfile.Conversational
        is CallAudioSessionState.Failed,
        is CallAudioSessionState.Transitioning,
        CallAudioSessionState.Released,
        -> false
    }

sealed interface CallAudioTransitionOutcome {
    data object Applied : CallAudioTransitionOutcome

    data object AlreadyActive : CallAudioTransitionOutcome

    data object NotApplicable : CallAudioTransitionOutcome

    data class Recovered(
        val error: Throwable,
    ) : CallAudioTransitionOutcome

    data class Failed(
        val error: Throwable,
    ) : CallAudioTransitionOutcome
}

fun interface ListenOnlyEligibility {
    fun isEligible(activeRoute: AudioRoute): Boolean
}

class BluetoothListenOnlyEligibility : ListenOnlyEligibility {
    override fun isEligible(activeRoute: AudioRoute): Boolean = activeRoute == AudioRoute.Bluetooth
}

interface CallAudioSessionController {
    val state: StateFlow<CallAudioSessionState>

    suspend fun activate()

    suspend fun transitionTo(profile: CallAudioProfile): CallAudioTransitionOutcome

    suspend fun release()
}

class CoordinatedCallAudioSessionController(
    private val modeController: CallAudioModeController,
    private val focusManager: CallAudioFocusManager,
    private val routeController: AudioRouteController,
    private val listenOnlyEligibility: ListenOnlyEligibility,
) : CallAudioSessionController {
    private val transitionMutex = Mutex()
    private val _state = MutableStateFlow<CallAudioSessionState>(CallAudioSessionState.Released)

    override val state: StateFlow<CallAudioSessionState> = _state.asStateFlow()

    override suspend fun activate() = transitionMutex.withLock {
        when (val current = _state.value) {
            CallAudioSessionState.Released -> Unit
            is CallAudioSessionState.Active -> {
                check(current.profile == CallAudioProfile.Conversational) {
                    "Call audio session is already active with ${current.profile}"
                }
                return@withLock
            }
            else -> error("Call audio session cannot activate from $current")
        }

        _state.value = CallAudioSessionState.Transitioning(
            from = null,
            target = CallAudioProfile.Conversational,
        )
        try {
            applyProfile(CallAudioProfile.Conversational)
            _state.value = CallAudioSessionState.Active(CallAudioProfile.Conversational)
        } catch (error: Throwable) {
            withContext(NonCancellable) { releaseOwnedResources() }?.let(error::addSuppressed)
            _state.value = CallAudioSessionState.Released
            throw error
        }
    }

    override suspend fun transitionTo(profile: CallAudioProfile): CallAudioTransitionOutcome =
        transitionMutex.withLock {
            val startingState = _state.value
            val previous = startingState.currentProfile
                ?: return@withLock CallAudioTransitionOutcome.Failed(
                    IllegalStateException("Call audio session is not active"),
                )
            if (
                previous == profile &&
                startingState !is CallAudioSessionState.Failed &&
                focusManager.state.value == CallAudioFocusState.Granted
            ) {
                _state.value = CallAudioSessionState.Active(profile)
                return@withLock CallAudioTransitionOutcome.AlreadyActive
            }
            if (
                profile == CallAudioProfile.ListenOnly &&
                !listenOnlyEligibility.isEligible(routeController.activeRoute.value)
            ) {
                return@withLock CallAudioTransitionOutcome.NotApplicable
            }

            _state.value = CallAudioSessionState.Transitioning(from = previous, target = profile)
            try {
                applyProfile(profile)
                _state.value = CallAudioSessionState.Active(profile)
                CallAudioTransitionOutcome.Applied
            } catch (transitionError: Throwable) {
                val rollbackError = withContext(NonCancellable) {
                    runCatching { applyProfile(previous) }.exceptionOrNull()
                }
                if (transitionError is CancellationException) {
                    if (rollbackError == null) {
                        _state.value = CallAudioSessionState.Active(previous)
                    } else {
                        transitionError.addSuppressed(rollbackError)
                        _state.value = CallAudioSessionState.Failed(
                            lastKnownProfile = previous,
                            failedTarget = profile,
                            reason = transitionError.message,
                        )
                    }
                    throw transitionError
                }
                if (rollbackError == null) {
                    _state.value = CallAudioSessionState.Degraded(
                        profile = previous,
                        failedTarget = profile,
                        reason = transitionError.message,
                    )
                    CallAudioTransitionOutcome.Recovered(transitionError)
                } else {
                    transitionError.addSuppressed(rollbackError)
                    _state.value = CallAudioSessionState.Failed(
                        lastKnownProfile = previous,
                        failedTarget = profile,
                        reason = transitionError.message,
                    )
                    CallAudioTransitionOutcome.Failed(transitionError)
                }
            }
        }

    override suspend fun release() = transitionMutex.withLock {
        val previous = _state.value.currentProfile
        _state.value = CallAudioSessionState.Transitioning(from = previous, target = null)
        val failure = withContext(NonCancellable) { releaseOwnedResources() }
        _state.value = CallAudioSessionState.Released
        failure?.let { throw it }
        Unit
    }

    private suspend fun applyProfile(profile: CallAudioProfile) {
        when (profile) {
            CallAudioProfile.Conversational -> {
                modeController.apply(profile)
                check(focusManager.requestFocus(profile)) {
                    "Unable to gain conversational audio focus"
                }
                routeController.restorePreferredRoute()
            }
            CallAudioProfile.ListenOnly -> {
                routeController.releaseCallRoute()
                modeController.apply(profile)
                check(focusManager.requestFocus(profile)) {
                    "Unable to gain listen-only audio focus"
                }
            }
        }
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
