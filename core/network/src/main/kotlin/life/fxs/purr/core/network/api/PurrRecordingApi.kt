package life.fxs.purr.core.network.api

import life.fxs.purr.core.network.model.CallRecordingsResponseDto
import life.fxs.purr.core.network.model.RecordingDownloadDto
import life.fxs.purr.core.network.model.RecordingLibraryResponseDto
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PurrRecordingApi {
    @GET("calls/{callId}/recordings")
    suspend fun getRecordings(@Path("callId") callId: String): CallRecordingsResponseDto

    @POST("calls/{callId}/recordings/{recordingId}/download")
    suspend fun createRecordingDownload(
        @Path("callId") callId: String,
        @Path("recordingId") recordingId: String,
    ): RecordingDownloadDto

    @GET("recordings")
    suspend fun getRecordingLibrary(
        @Query("limit") limit: Int,
        @Query("before") cursor: String? = null,
    ): RecordingLibraryResponseDto
}
