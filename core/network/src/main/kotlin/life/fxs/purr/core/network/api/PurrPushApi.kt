package life.fxs.purr.core.network.api

import life.fxs.purr.core.network.model.PushDeviceRegistrationDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PUT
import retrofit2.http.Path

interface PurrPushApi {
    @PUT("devices/push/{installationId}")
    suspend fun register(
        @Path("installationId") installationId: String,
        @Body request: PushDeviceRegistrationDto,
    )

    @DELETE("devices/push/{installationId}")
    suspend fun unregister(@Path("installationId") installationId: String)
}
