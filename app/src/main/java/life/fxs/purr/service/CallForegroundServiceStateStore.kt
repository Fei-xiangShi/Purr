package life.fxs.purr.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.core.media.service.ForegroundCallServiceState
import life.fxs.purr.core.model.CallDirection

/** Bridges the asynchronous Android Service lifecycle to the process-local call runtime. */
@Singleton
class CallForegroundServiceStateStore @Inject constructor() {
    private val mutableState = MutableStateFlow(ForegroundCallServiceState())

    val state: StateFlow<ForegroundCallServiceState> = mutableState.asStateFlow()

    /**
     * Records a service that has successfully entered foreground mode.
     *
     * A stale start command must never replace the call that currently owns
     * the foreground service. The service is single-instance, so keeping this
     * transition serialized also makes duplicate start intents idempotent.
     */
    @Synchronized
    fun markStarted(callId: String, direction: CallDirection): Boolean {
        require(callId.isNotBlank()) { "Call id must not be blank" }
        val existingCallId = mutableState.value.activeCallId
        if (existingCallId != null && existingCallId != callId) return false
        mutableState.value = ForegroundCallServiceState(activeCallId = callId, direction = direction)
        return true
    }

    @Synchronized
    fun isActiveFor(callId: String?): Boolean =
        !callId.isNullOrBlank() && mutableState.value.activeCallId == callId

    @Synchronized
    fun markStopped(callId: String?) {
        if (callId == null || mutableState.value.activeCallId == callId) {
            mutableState.value = ForegroundCallServiceState()
        }
    }
}
