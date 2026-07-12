package life.fxs.purr.core.network.api

import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.model.ChangePasswordRequestDto
import life.fxs.purr.core.network.model.UpdateProfileRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MultipartBody

interface PurrAccountApi {
    @GET("me")
    suspend fun getMe(): SelfProfile

    @GET("pair")
    suspend fun getPair(): PairBond

    @PUT("me/password")
    suspend fun changePassword(@Body request: ChangePasswordRequestDto)

    @PUT("me/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequestDto): SelfProfile

    @Multipart
    @PUT("me/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): SelfProfile
}
