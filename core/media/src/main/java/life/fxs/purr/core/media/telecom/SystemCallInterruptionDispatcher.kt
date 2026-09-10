package life.fxs.purr.core.media.telecom

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult

interface SystemCallInterruptionHandler {
    suspend fun onSetInactive(request: SystemCallInterruptionRequest): SystemCallInterruptionResult

    suspend fun onSetActive(request: SystemCallInterruptionRequest): SystemCallInterruptionResult
}

/**
 * Runtime registration avoids a Hilt constructor cycle:
 * controller -> dispatcher <- app handler -> repository -> runtime -> controller.
 */
@Singleton
class SystemCallInterruptionDispatcher @Inject constructor() {
    private val handler = AtomicReference<SystemCallInterruptionHandler?>(null)

    val isRegistered: Boolean
        get() = handler.get() != null

    fun register(candidate: SystemCallInterruptionHandler) {
        val current = handler.get()
        if (current === candidate) return
        check(current == null && handler.compareAndSet(null, candidate)) {
            "A system-call interruption handler is already registered"
        }
    }

    suspend fun dispatchInactive(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult = requireHandler().onSetInactive(request)

    suspend fun dispatchActive(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult = requireHandler().onSetActive(request)

    private fun requireHandler(): SystemCallInterruptionHandler = checkNotNull(handler.get()) {
        "System-call interruption handling is not initialized"
    }
}
