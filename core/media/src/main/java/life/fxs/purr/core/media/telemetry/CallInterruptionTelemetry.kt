package life.fxs.purr.core.media.telemetry

enum class CallInterruptionTransitionPhase {
    Suspend,
    Resume,
}

data class CallInterruptionTransitionContext(
    val callId: String,
    val operationId: String,
    val phase: CallInterruptionTransitionPhase,
    val lifecycleGeneration: Long,
    val mediaGeneration: Long,
)

interface CallInterruptionTelemetry {
    fun recordTelecomCallback(
        callId: String,
        operationId: String,
        sequence: Long,
        callback: String,
        result: String,
        elapsedMillis: Long,
    )

    fun startTransition(
        context: CallInterruptionTransitionContext,
    ): CallInterruptionTelemetryOperation
}

interface CallInterruptionTelemetryOperation {
    fun recordAttempt(
        attempt: Int,
        retriesRemaining: Int,
        result: String,
        reasonCode: String? = null,
    )

    fun finish(
        result: String,
        reasonCode: String? = null,
        throwable: Throwable? = null,
        finalFailure: Boolean = false,
    )
}

object NoOpCallInterruptionTelemetry : CallInterruptionTelemetry {
    override fun recordTelecomCallback(
        callId: String,
        operationId: String,
        sequence: Long,
        callback: String,
        result: String,
        elapsedMillis: Long,
    ) = Unit

    override fun startTransition(
        context: CallInterruptionTransitionContext,
    ): CallInterruptionTelemetryOperation = NoOpCallInterruptionTelemetryOperation
}

object NoOpCallInterruptionTelemetryOperation : CallInterruptionTelemetryOperation {
    override fun recordAttempt(
        attempt: Int,
        retriesRemaining: Int,
        result: String,
        reasonCode: String?,
    ) = Unit

    override fun finish(
        result: String,
        reasonCode: String?,
        throwable: Throwable?,
        finalFailure: Boolean,
    ) = Unit
}
