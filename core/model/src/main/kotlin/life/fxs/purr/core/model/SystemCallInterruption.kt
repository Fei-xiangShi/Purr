package life.fxs.purr.core.model

/** Identity shared by Telecom, domain, and media layers for one inactive/active cycle. */
data class SystemCallInterruptionRequest(
    val callId: String,
    val operationId: String,
    val telecomSequence: Long,
)

/** Bounded acknowledgement returned to an Android Telecom callback. */
sealed interface SystemCallInterruptionResult {
    data object Applied : SystemCallInterruptionResult
    data object RetryScheduled : SystemCallInterruptionResult
    data object TerminationScheduled : SystemCallInterruptionResult
    data class Degraded(val reasonCode: String) : SystemCallInterruptionResult
    data class Ignored(val reasonCode: String) : SystemCallInterruptionResult
}

enum class SystemCallInterruptionPhase {
    Suspended,
    Resuming,
    Active,
}
