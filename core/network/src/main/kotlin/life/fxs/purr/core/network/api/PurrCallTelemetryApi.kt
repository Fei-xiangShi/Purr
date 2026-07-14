package life.fxs.purr.core.network.api

import life.fxs.purr.core.network.model.CallTelemetryRequestDto
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface PurrCallTelemetryApi {
    @POST("calls/{callId}/telemetry")
    suspend fun report(
        @Path("callId") callId: String,
        @Body sample: CallTelemetryRequestDto,
    )
}
