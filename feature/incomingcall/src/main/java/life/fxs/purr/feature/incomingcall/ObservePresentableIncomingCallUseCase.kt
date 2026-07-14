package life.fxs.purr.feature.incomingcall

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase

/**
 * The single presentation source for incoming calls.
 *
 * Realtime remains the authority for a ringing candidate. A matching or otherwise active local
 * call session is stronger evidence that the app is already handling a call, so presentation is
 * suppressed consistently for navigation, the full-screen prompt, and background reminders.
 */
class ObservePresentableIncomingCallUseCase @Inject constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val observeRealtimeState: ObserveRealtimeStateUseCase,
    private val observeCallState: ObserveCallStateUseCase,
) {
    operator fun invoke(): Flow<IncomingCall?> = combine(
        observeAuthSession(),
        observeRealtimeState(),
        observeCallState(),
    ) { authSession, realtimeState, localSession ->
        PresentableIncomingCallPolicy.resolve(
            candidate = realtimeState.incomingCallCandidate,
            localSession = localSession,
            isAuthenticated = authSession != null,
        )
    }.distinctUntilChanged()
}

internal object PresentableIncomingCallPolicy {
    fun resolve(
        candidate: IncomingCall?,
        localSession: CallSession?,
        isAuthenticated: Boolean = true,
    ): IncomingCall? {
        if (!isAuthenticated) return null
        candidate ?: return null
        localSession ?: return candidate
        if (localSession.callId == candidate.callId) return null
        return candidate.takeUnless { localSession.connectionState.isOngoing }
    }
}
