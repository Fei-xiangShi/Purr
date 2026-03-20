package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.AuthRepository

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke() = authRepository.logout()
}
