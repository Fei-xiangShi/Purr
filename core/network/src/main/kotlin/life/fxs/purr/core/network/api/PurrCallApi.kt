package life.fxs.purr.core.network.api

import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.core.network.model.ActiveCallResponseDto
import life.fxs.purr.core.network.model.SessionRequestDto
import life.fxs.purr.core.network.model.SessionResponseDto
import life.fxs.purr.core.network.model.CallRecordingsResponseDto
import life.fxs.purr.core.network.model.RecordingDownloadDto
import life.fxs.purr.core.network.model.RecordingLibraryResponseDto
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

    @GET("calls/active")
    suspend fun getActiveCall(): ActiveCallResponseDto

    @GET("calls/{callId}/recordings")
    suspend fun getRecordings(@Path("callId") callId: String): CallRecordingsResponseDto

    @POST("calls/{callId}/recordings/{recordingId}/download")
    suspend fun createRecordingDownload(
        @Path("callId") callId: String,
        @Path("recordingId") recordingId: String,
    ): RecordingDownloadDto

    @GET("recordings")
    suspend fun getRecordingLibrary(
        @retrofit2.http.Query("limit") limit: Int,
        @retrofit2.http.Query("before") cursor: String? = null,
    ): RecordingLibraryResponseDto
}
