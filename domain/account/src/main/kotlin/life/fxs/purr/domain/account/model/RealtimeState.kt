package life.fxs.purr.domain.account.model

data class IncomingCall(
    val callId: String,
    val pairId: String,
    val callerUserId: String,
    val startedAtEpochMillis: Long,
)

data class RealtimeState(
    val isConnected: Boolean = false,
    val partnerOnline: Boolean? = null,
    val incomingCallCandidate: IncomingCall? = null,
)
