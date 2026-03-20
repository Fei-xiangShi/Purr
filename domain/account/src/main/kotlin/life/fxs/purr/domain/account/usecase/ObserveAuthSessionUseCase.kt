package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.AuthRepository

class ObserveAuthSessionUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    operator fun invoke() = authRepository.observeSession()
}
