package life.fxs.purr.domain.call.model

import life.fxs.purr.core.model.CallDirection

/** Explicitly separates creating a new call from opening an existing call. */
sealed interface CallPreparationRequest {
    val pairId: String
    val recordingConsent: Boolean
    val remoteDisplayName: String
    val direction: CallDirection

    data class NewOutgoing(
        override val pairId: String,
        override val recordingConsent: Boolean,
        override val remoteDisplayName: String = "Purr",
    ) : CallPreparationRequest {
        override val direction: CallDirection = CallDirection.Outgoing
    }

    data class Existing(
        override val pairId: String,
        val callId: String,
        override val direction: CallDirection,
        override val recordingConsent: Boolean,
        override val remoteDisplayName: String = "Purr",
    ) : CallPreparationRequest
}
