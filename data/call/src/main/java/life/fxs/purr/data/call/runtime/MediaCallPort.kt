package life.fxs.purr.data.call.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.select
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.SystemCallInterruptionPhase

class CallTerminationSignal {
    private val requested = CompletableDeferred<Unit>()

    val isRequested: Boolean
        get() = requested.isCompleted

    fun request() {
        requested.complete(Unit)
    }

    fun throwIfRequested() {
        if (isRequested) throw CallTerminationException()
    }

    suspend fun <T> runStage(block: suspend () -> T): T = coroutineScope {
        throwIfRequested()
        val stage = async(start = CoroutineStart.DEFAULT) { block() }
        select {
            stage.onAwait { it }
            requested.onAwait {
                stage.cancel(CallTerminationException())
                try {
                    stage.await()
                } catch (_: CancellationException) {
                    // The stage acknowledged cancellation.
                }
                // A provider may suppress cancellation while finishing synchronous cleanup.
                // Termination still wins once that cleanup returns.
                throw CallTerminationException()
            }
        }
    }
}

internal class CallTerminationException : CancellationException("Call termination requested")

/**
 * Transport-neutral commands understood by the process-local media runtime.
 *
 * The media adapter must not receive the domain call aggregate. Keeping this contract
 * limited to identifiers, credentials and media settings makes a provider adapter
 * replaceable (for example LiveKit with another RTC implementation).
 */
sealed interface MediaCallCommand {
    val callId: String

    data class Connect(
        override val callId: String,
        val pairId: String,
        val localIdentity: String,
        val connection: CallMediaConnection,
        val remoteDisplayName: String,
        val direction: CallDirection,
        internal val terminationSignal: CallTerminationSignal = CallTerminationSignal(),
    ) : MediaCallCommand

    /**
     * Re-establishes only the media room of an already running call after a recoverable
     * transport loss. Foreground service, Telecom and audio ownership are never touched.
     */
    data class Rejoin(
        override val callId: String,
        val connection: CallMediaConnection,
        /** The user's microphone intent captured before the loss. */
        val microphoneEnabled: Boolean,
        /** Non-null while a system call keeps the physical media gate closed. */
        val suspendedOperationId: String? = null,
        internal val terminationSignal: CallTerminationSignal = CallTerminationSignal(),
    ) : MediaCallCommand

    data class Disconnect(
        override val callId: String,
    ) : MediaCallCommand

    data class SetMuted(
        override val callId: String,
        val muted: Boolean,
    ) : MediaCallCommand
}

/**
 * Facts emitted by a media provider. Events carry a provider-owned generation so
 * delayed callbacks from an old room can never be mistaken for the current room.
 */
sealed interface MediaCallEvent {
    val callId: String
    val generation: Long

    data class Connected(
        override val callId: String,
        override val generation: Long,
        val localIdentity: String,
        val remoteIdentity: String?,
        val remoteParticipantConnected: Boolean,
    ) : MediaCallEvent

    data class Reconnecting(
        override val callId: String,
        override val generation: Long,
    ) : MediaCallEvent

    data class Reconnected(
        override val callId: String,
        override val generation: Long,
        val remoteIdentity: String?,
        val remoteParticipantConnected: Boolean,
    ) : MediaCallEvent

    data class ParticipantChanged(
        override val callId: String,
        override val generation: Long,
        val remoteIdentity: String?,
        val remoteParticipantConnected: Boolean,
    ) : MediaCallEvent

    data class AudioStateChanged(
        override val callId: String,
        override val generation: Long,
        val muted: Boolean,
    ) : MediaCallEvent

    data class NetworkQualityChanged(
        override val callId: String,
        override val generation: Long,
        val direction: NetworkQualityDirection,
        /** Null means the SDK reported UNKNOWN; consumers must show "no data". */
        val score: Int?,
        val sampledAtEpochMillis: Long,
    ) : MediaCallEvent

    data class RemoteSystemCallInterruptionChanged(
        override val callId: String,
        override val generation: Long,
        val operationId: String,
        val phase: SystemCallInterruptionPhase,
        val degraded: Boolean,
    ) : MediaCallEvent

    data class Disconnected(
        override val callId: String,
        override val generation: Long,
    ) : MediaCallEvent

    data class Failed(
        override val callId: String,
        override val generation: Long,
        val reason: String?,
    ) : MediaCallEvent

    /**
     * The media room is gone for a recoverable (network class) reason. The provider has already
     * detached the old room; the call owner decides whether to rejoin or to terminate.
     */
    data class ConnectionLost(
        override val callId: String,
        override val generation: Long,
        val reasonCode: String,
    ) : MediaCallEvent
}

/** Vendor-neutral boundary consumed by the Android call runtime coordinator. */
interface MediaCallPort {
    val events: Flow<MediaCallEvent>

    suspend fun execute(command: MediaCallCommand)

    suspend fun suspendForSystemCall(
        request: MediaSystemCallSuspendRequest,
    ): MediaSystemCallInterruptionResult

    suspend fun resumeAfterSystemCall(
        request: MediaSystemCallResumeRequest,
    ): MediaSystemCallInterruptionResult
}

data class MediaSystemCallSuspendRequest(
    val callId: String,
    val expectedGeneration: Long,
    val operationId: String,
)

data class MediaSystemCallResumeRequest(
    val callId: String,
    val expectedGeneration: Long,
    val operationId: String,
    val enableMicrophone: Boolean,
)

sealed interface MediaSystemCallInterruptionResult {
    val generation: Long?

    data class Applied(
        override val generation: Long,
    ) : MediaSystemCallInterruptionResult

    data class Degraded(
        override val generation: Long,
        val reasonCode: String,
    ) : MediaSystemCallInterruptionResult

    data class PausedReconnecting(
        override val generation: Long,
    ) : MediaSystemCallInterruptionResult

    data class Failed(
        override val generation: Long,
        val reasonCode: String,
    ) : MediaSystemCallInterruptionResult

    data class TerminalFailure(
        override val generation: Long,
        val reasonCode: String,
    ) : MediaSystemCallInterruptionResult

    data class Stale(
        override val generation: Long?,
        val reasonCode: String,
    ) : MediaSystemCallInterruptionResult
}

/** Local participant quality describes uplink; the remote participant's describes downlink. */
enum class NetworkQualityDirection { Uplink, Downlink }
