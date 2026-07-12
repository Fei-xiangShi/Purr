package life.fxs.purr.core.network.api

import life.fxs.purr.core.network.model.CallRecordingsResponseDto
import life.fxs.purr.core.network.model.RecordingDownloadDto
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PurrRecordingApi {
    @GET("calls/{callId}/recordings")
    suspend fun getRecordings(@Path("callId") callId: String): CallRecordingsResponseDto

    @POST("calls/{callId}/recordings/{recordingId}/download")
    suspend fun createRecordingDownload(
        @Path("callId") callId: String,
        @Path("recordingId") recordingId: String,
    ): RecordingDownloadDto
}
