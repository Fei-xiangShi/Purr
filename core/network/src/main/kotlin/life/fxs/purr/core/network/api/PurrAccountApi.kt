package life.fxs.purr.core.network.api

import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.SelfProfile
import retrofit2.http.GET

interface PurrAccountApi {
    @GET("me")
    suspend fun getMe(): SelfProfile

    @GET("pair")
    suspend fun getPair(): PairBond
}
