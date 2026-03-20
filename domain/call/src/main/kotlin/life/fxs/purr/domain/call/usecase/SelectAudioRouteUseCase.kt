package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.repository.CallRepository

class SelectAudioRouteUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke(route: AudioRoute) = callRepository.selectAudioRoute(route)
}
