package life.fxs.purr.data.call.runtime

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.model.CallDirection

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
        val remoteDisplayName: String = "Purr",
        val direction: CallDirection = CallDirection.Outgoing,
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
        val uplinkScore: Int,
        val downlinkScore: Int,
        val sampledAtEpochMillis: Long,
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
}

/** Vendor-neutral boundary consumed by the Android call runtime coordinator. */
interface MediaCallPort {
    val events: Flow<MediaCallEvent>

    suspend fun execute(command: MediaCallCommand)
}
