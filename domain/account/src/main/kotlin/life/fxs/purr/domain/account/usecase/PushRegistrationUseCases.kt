package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.PushRegistrationRepository

class RegisterPushInstallationUseCase @Inject constructor(
    private val repository: PushRegistrationRepository,
) {
    suspend operator fun invoke(installationId: String, token: String) =
        repository.register(installationId, token)
}

class UnregisterPushInstallationUseCase @Inject constructor(
    private val repository: PushRegistrationRepository,
) {
    suspend operator fun invoke(installationId: String) = repository.unregister(installationId)
}
