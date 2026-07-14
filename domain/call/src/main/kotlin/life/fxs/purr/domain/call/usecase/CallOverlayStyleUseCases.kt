package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.model.CallOverlayStyle
import life.fxs.purr.domain.call.repository.CallOverlayStyleRepository

class ObserveCallOverlayStyleUseCase @Inject constructor(
    private val repository: CallOverlayStyleRepository,
) {
    operator fun invoke() = repository.style
}

class SetCallOverlayStyleUseCase @Inject constructor(
    private val repository: CallOverlayStyleRepository,
) {
    operator fun invoke(style: CallOverlayStyle) = repository.setStyle(style)
}
