package life.fxs.purr.core.network.api

import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.core.network.model.SessionRequestDto
import life.fxs.purr.core.network.model.SessionResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PurrCallApi {
    @GET("me")
    suspend fun getMe(): SelfProfile

    @GET("pair")
    suspend fun getPair(): PairBond

    @POST("calls/session")
    suspend fun createSession(@Body request: SessionRequestDto): SessionResponseDto

    @POST("calls/{callId}/end")
    suspend fun endCall(@Path("callId") callId: String)

    @GET("calls/{callId}")
    suspend fun getCall(@Path("callId") callId: String): CallStatusDto
}
