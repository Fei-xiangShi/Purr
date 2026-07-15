package life.fxs.purr.data.account.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrPushApi
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.network.model.PushDeviceRegistrationDto
import life.fxs.purr.domain.account.repository.PushRegistrationRepository

@Singleton
class ApiPushRegistrationRepository @Inject constructor(
    private val api: PurrPushApi,
) : PushRegistrationRepository {
    override suspend fun register(installationId: String, token: String): AppResult<Unit> =
        networkResult {
            api.register(
                installationId = installationId,
                request = PushDeviceRegistrationDto(PROVIDER_FCM, token),
            )
        }

    override suspend fun unregister(installationId: String): AppResult<Unit> =
        networkResult { api.unregister(installationId) }

    private suspend fun networkResult(block: suspend () -> Unit): AppResult<Unit> = try {
        block()
        AppResult.Success(Unit)
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AppResult.Failure(throwable.asAppError())
    }

    private companion object {
        const val PROVIDER_FCM = "FCM"
    }
}
