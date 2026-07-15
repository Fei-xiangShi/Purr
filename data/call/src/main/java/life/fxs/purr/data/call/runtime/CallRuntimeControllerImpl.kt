package life.fxs.purr.data.call.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioProfile
import life.fxs.purr.core.media.audio.CallAudioSessionController
import life.fxs.purr.core.media.audio.CallAudioTransitionOutcome
import life.fxs.purr.core.media.audio.currentProfile
import life.fxs.purr.core.media.audio.isConversationalReady
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.model.AudioRoute

@Singleton
class CallRuntimeControllerImpl @Inject constructor(
    private val mediaCallPort: MediaCallPort,
    private val audioRouteController: AudioRouteController,
    private val callAudioSessionController: CallAudioSessionController,
    private val callServiceController: CallServiceController,
    private val systemCallController: SystemCallController,
) : CallRuntimeController {
    override val mediaEvents: Flow<MediaCallEvent> = mediaCallPort.events
    private val lifecycleMutex = Mutex()
    private var activeCallId: String? = null

    override suspend fun execute(command: MediaCallCommand) = lifecycleMutex.withLock {
        when (command) {
            is MediaCallCommand.Connect -> connectLocked(command)
            is MediaCallCommand.Disconnect -> disconnectLocked(command)
            is MediaCallCommand.SetMuted -> setMutedLocked(command)
        }
    }

    private suspend fun connectLocked(command: MediaCallCommand.Connect) {
        val existingCallId = activeCallId
        if (existingCallId != null) {
            // The same call command may be delivered more than once by a resumed
            // screen or notification. It is already owned by this runtime, so the
            // duplicate is a no-op; a different call must never replace it.
            check(existingCallId == command.callId) { "A call runtime is already active" }
            return
        }
        activeCallId = command.callId
        try {
            // Start the foreground service while the user-initiated call is still eligible
            // under Android's while-in-use/background-start rules.
            callServiceController.startForegroundCall(command.callId, command.pairId)
            systemCallController.startCall(
                SystemCallDescriptor(
                    callId = command.callId,
                    pairId = command.pairId,
                    remoteDisplayName = command.remoteDisplayName,
                    direction = command.direction,
                ),
            )
            systemCallController.activateCall(command.callId)
            callAudioSessionController.activate()
            mediaCallPort.execute(command)
        } catch (throwable: Throwable) {
            releaseResourcesLocked()?.let(throwable::addSuppressed)
            activeCallId = null
            throw throwable
        }
    }

    private suspend fun disconnectLocked(command: MediaCallCommand.Disconnect) {
        val currentCallId = activeCallId
        if (currentCallId == null) {
            releaseResourcesLocked(callIdOverride = command.callId)?.let { throw it }
            return
        }
        check(currentCallId == command.callId) { "The requested call is not active" }
        var failure: Throwable? = null
        try {
            mediaCallPort.execute(command)
        } catch (error: Throwable) {
            failure = error
        }
        releaseResourcesLocked()?.let { error ->
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        activeCallId = null
        failure?.let { throw it }
    }

    private suspend fun setMutedLocked(command: MediaCallCommand.SetMuted) {
        check(activeCallId == command.callId) { "No active call runtime" }
        if (command.muted) {
            // Privacy is authoritative: stop transmission before changing the Bluetooth profile.
            mediaCallPort.execute(command)
            callAudioSessionController.transitionTo(CallAudioProfile.ListenOnly)
            return
        }

        val previousProfile = callAudioSessionController.state.value.currentProfile
        val transition = callAudioSessionController.transitionTo(CallAudioProfile.Conversational)
        transition.requireReached(CallAudioProfile.Conversational)
        try {
            // The microphone is enabled only after communication mode and routing are ready.
            mediaCallPort.execute(command)
        } catch (error: Throwable) {
            if (previousProfile == CallAudioProfile.ListenOnly) {
                val rollback = withContext(NonCancellable) {
                    callAudioSessionController.transitionTo(CallAudioProfile.ListenOnly)
                }
                if (rollback is CallAudioTransitionOutcome.Failed) {
                    error.addSuppressed(rollback.error)
                }
            }
            throw error
        }
    }

    override suspend fun releaseResources() = lifecycleMutex.withLock {
        val failure = releaseResourcesLocked()
        activeCallId = null
        failure?.let { throw it }
        Unit
    }

    private suspend fun releaseResourcesLocked(callIdOverride: String? = null): Throwable? {
        var failure: Throwable? = null
        try {
            callAudioSessionController.release()
        } catch (error: Throwable) {
            failure = error
        }
        val callId = callIdOverride ?: activeCallId
        if (callId != null) {
            try {
                systemCallController.disconnectCall(callId)
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            try {
                callServiceController.stopForegroundCall(callId)
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        return failure
    }

    override suspend fun selectAudioRoute(route: AudioRoute) = lifecycleMutex.withLock {
        check(activeCallId != null) { "No active call runtime" }
        check(callAudioSessionController.state.value.isConversationalReady) {
            "Audio routes can only be selected in conversational mode"
        }
        audioRouteController.selectRoute(route)
    }
}

private fun CallAudioTransitionOutcome.requireReached(target: CallAudioProfile) {
    when (this) {
        CallAudioTransitionOutcome.Applied,
        CallAudioTransitionOutcome.AlreadyActive,
        -> Unit
        CallAudioTransitionOutcome.NotApplicable -> error("Audio profile $target is not applicable")
        is CallAudioTransitionOutcome.Recovered -> throw IllegalStateException(
            "Audio profile $target failed and was rolled back",
            error,
        )
        is CallAudioTransitionOutcome.Failed -> throw IllegalStateException(
            "Audio profile $target and its rollback both failed",
            error,
        )
    }
}
