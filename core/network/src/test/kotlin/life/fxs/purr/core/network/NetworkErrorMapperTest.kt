package life.fxs.purr.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import org.junit.Test
import life.fxs.purr.core.common.AppError

class NetworkErrorMapperTest {
    @Test
    fun `maps unauthorized response without leaking retrofit to common`() {
        val response = Response.error<String>(
            401,
            "{\"message\":\"expired\"}".toResponseBody("application/json".toMediaType()),
        )

        val error = HttpException(response).asAppError()

        assertThat(error).isEqualTo(AppError.Unauthorized("expired"))
    }

    @Test
    fun `maps transport failure`() {
        val error = IOException("offline").asAppError()

        assertThat(error).isEqualTo(AppError.Network("offline"))
    }
}
