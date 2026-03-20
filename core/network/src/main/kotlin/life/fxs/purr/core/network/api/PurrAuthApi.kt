package life.fxs.purr.core.network.api

import life.fxs.purr.core.network.model.AuthSessionDto
import life.fxs.purr.core.network.model.LoginRequestDto
import life.fxs.purr.core.network.model.RefreshRequestDto
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface PurrAuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequestDto): AuthSessionDto

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequestDto): AuthSessionDto

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String)
}
