package life.fxs.purr.domain.call.model

data class CallInterruptionState(
    val local: LocalCallInterruption = LocalCallInterruption.None,
    val remote: RemoteCallInterruption = RemoteCallInterruption.None,
)

sealed interface LocalCallInterruption {
    data object None : LocalCallInterruption

    data class Suspending(
        val operationId: String,
    ) : LocalCallInterruption

    data class Suspended(
        val operationId: String,
        val degraded: Boolean,
    ) : LocalCallInterruption

    data class Resuming(
        val operationId: String,
        val attemptsStarted: Int,
        val retriesRemaining: Int,
    ) : LocalCallInterruption
}

sealed interface RemoteCallInterruption {
    data object None : RemoteCallInterruption

    data class Suspended(
        val operationId: String,
        val degraded: Boolean,
    ) : RemoteCallInterruption

    data class Resuming(
        val operationId: String,
    ) : RemoteCallInterruption
}

val LocalCallInterruption.isSystemSuspended: Boolean
    get() = this != LocalCallInterruption.None
