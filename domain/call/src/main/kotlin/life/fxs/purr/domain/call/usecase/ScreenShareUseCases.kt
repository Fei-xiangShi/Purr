package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.repository.ScreenShareRepository

class ObserveScreenShareUseCase @Inject constructor(
    private val repository: ScreenShareRepository,
) {
    operator fun invoke(callId: String) = repository.observe(callId)
}

class CreateScreenShareUseCase @Inject constructor(
    private val repository: ScreenShareRepository,
) {
    suspend operator fun invoke(callId: String, source: ScreenShareSource) =
        repository.create(callId, source)
}

class RefreshScreenShareUseCase @Inject constructor(
    private val repository: ScreenShareRepository,
) {
    suspend operator fun invoke(callId: String) = repository.refresh(callId)
}

class StopScreenShareUseCase @Inject constructor(
    private val repository: ScreenShareRepository,
) {
    suspend operator fun invoke(callId: String) = repository.stop(callId)
}
