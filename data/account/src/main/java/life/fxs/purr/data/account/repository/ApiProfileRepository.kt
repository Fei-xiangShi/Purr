package life.fxs.purr.data.account.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.core.network.model.UpdateProfileRequestDto
import life.fxs.purr.data.account.network.SessionWriter
import life.fxs.purr.domain.account.repository.ProfileRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class ApiProfileRepository @Inject constructor(
    private val accountApi: PurrAccountApi,
    private val sessionWriter: SessionWriter,
) : ProfileRepository {
    override suspend fun uploadAvatar(contentType: String, bytes: ByteArray): AppResult<SelfProfile> {
        return try {
            val avatar = bytes.toRequestBody(contentType.toMediaType())
            val filename = when (contentType.lowercase()) {
                "image/jpeg" -> "avatar.jpg"
                "image/png" -> "avatar.png"
                else -> error("Unsupported avatar content type: $contentType")
            }
            val profile = accountApi.uploadAvatar(
                MultipartBody.Part.createFormData("avatar", filename, avatar),
            )
            persistProfile(profile)
            AppResult.Success(profile)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            AppResult.Failure(exception.asAppError())
        }
    }

    override suspend fun updateDisplayName(displayName: String): AppResult<SelfProfile> {
        return try {
            val profile = accountApi.updateProfile(UpdateProfileRequestDto(displayName.trim()))
            persistProfile(profile)
            AppResult.Success(profile)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            AppResult.Failure(exception.asAppError())
        }
    }

    private suspend fun persistProfile(profile: SelfProfile) {
        sessionWriter.persistProfile(profile)
    }
}
