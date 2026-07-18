package life.fxs.purr.data.call.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioSessionController
import life.fxs.purr.core.media.audio.isReady
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
    private val logger: PurrLogger,
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
            runStage(command, "service.start") {
                callServiceController.startForegroundCall(command.callId, command.pairId)
            }
            runStage(command, "telecom.start") {
                systemCallController.startCall(
                    SystemCallDescriptor(
                        callId = command.callId,
                        pairId = command.pairId,
                        remoteDisplayName = command.remoteDisplayName,
                        direction = command.direction,
                    ),
                )
            }
            runStage(command, "telecom.activate") {
                systemCallController.activateCall(command.callId)
            }
            runStage(command, "audio.activate") { callAudioSessionController.activate() }
            runStage(command, "livekit.connect") { mediaCallPort.execute(command) }
            command.terminationSignal.throwIfRequested()
        } catch (throwable: Throwable) {
            withContext(NonCancellable) {
                releaseResourcesLocked()?.let(throwable::addSuppressed)
                activeCallId = null
            }
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
        mediaCallPort.execute(command)
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

    private suspend fun <T> runStage(
        command: MediaCallCommand.Connect,
        stage: String,
        block: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        logger.d(LOG_TAG, "callId=${command.callId} phase=$stage event=begin")
        return try {
            command.terminationSignal.runStage(block).also {
                logger.d(
                    LOG_TAG,
                    "callId=${command.callId} phase=$stage event=end elapsedMs=${elapsedMillis(startedAt)}",
                )
            }
        } catch (throwable: Throwable) {
            val event = if (throwable is CallTerminationException) "cancel" else "error"
            val message =
                "callId=${command.callId} phase=$stage event=$event elapsedMs=${elapsedMillis(startedAt)}"
            if (throwable is CallTerminationException) {
                logger.d(LOG_TAG, message)
            } else {
                logger.e(LOG_TAG, throwable, message)
            }
            throw throwable
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        const val LOG_TAG = "CallRuntime"
    }

    override suspend fun selectAudioRoute(route: AudioRoute) = lifecycleMutex.withLock {
        check(activeCallId != null) { "No active call runtime" }
        check(callAudioSessionController.state.value.isReady) {
            "Audio routes can only be selected while the audio session is active"
        }
        audioRouteController.selectRoute(route)
    }
}
