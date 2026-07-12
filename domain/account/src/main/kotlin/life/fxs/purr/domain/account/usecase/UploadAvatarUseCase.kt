package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.ProfileRepository

class UploadAvatarUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(contentType: String, bytes: ByteArray) =
        profileRepository.uploadAvatar(contentType, bytes)
}
