package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.RealtimeRepository

class ObserveRealtimeStateUseCase @Inject constructor(
    private val repository: RealtimeRepository,
) {
    operator fun invoke() = repository.observeState()
}

class StartRealtimeUpdatesUseCase @Inject constructor(
    private val repository: RealtimeRepository,
) {
    operator fun invoke() = repository.start()
}

class StopRealtimeUpdatesUseCase @Inject constructor(
    private val repository: RealtimeRepository,
) {
    operator fun invoke() = repository.stop()
}

class RefreshActiveCallUseCase @Inject constructor(
    private val repository: RealtimeRepository,
) {
    suspend operator fun invoke() = repository.refreshActiveCall()
}

class DeclineIncomingCallUseCase @Inject constructor(
    private val repository: RealtimeRepository,
) {
    suspend operator fun invoke(callId: String) = repository.declineIncomingCall(callId)
}

class ClearIncomingCallUseCase @Inject constructor(
    private val repository: RealtimeRepository,
) {
    operator fun invoke(callId: String) = repository.clearIncomingCall(callId)
}
