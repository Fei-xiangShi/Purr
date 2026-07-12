package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.AccountSecurityRepository

class ChangePasswordUseCase @Inject constructor(
    private val accountSecurityRepository: AccountSecurityRepository,
) {
    suspend operator fun invoke(currentPassword: String, newPassword: String) =
        accountSecurityRepository.changePassword(currentPassword, newPassword)
}
