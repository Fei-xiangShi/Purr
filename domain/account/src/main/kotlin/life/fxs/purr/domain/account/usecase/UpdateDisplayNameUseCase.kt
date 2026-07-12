package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.ProfileRepository

class UpdateDisplayNameUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(displayName: String) = profileRepository.updateDisplayName(displayName)
}
