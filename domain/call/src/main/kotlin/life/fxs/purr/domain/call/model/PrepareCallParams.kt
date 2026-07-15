package life.fxs.purr.domain.call.model

import life.fxs.purr.core.model.CallDirection

data class PrepareCallParams(
    val pairId: String,
    val recordingConsent: Boolean,
    val remoteDisplayName: String = "Purr",
    val direction: CallDirection = CallDirection.Outgoing,
    val expectedCallId: String? = null,
)
