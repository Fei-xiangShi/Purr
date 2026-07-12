package life.fxs.purr.core.network.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class ApiContractTest {
    @Test
    fun `account and authentication paths match the server contract`() {
        assertThat(PurrAuthApi::class.java.postPath("login")).isEqualTo("auth/login")
        assertThat(PurrAuthApi::class.java.postPath("refresh")).isEqualTo("auth/refresh")
        assertThat(PurrAuthApi::class.java.postPath("logout")).isEqualTo("auth/logout")
        assertThat(PurrAccountApi::class.java.getPath("getMe")).isEqualTo("me")
        assertThat(PurrAccountApi::class.java.getPath("getPair")).isEqualTo("pair")
    }

    @Test
    fun `call paths match the server contract`() {
        assertThat(PurrCallApi::class.java.postPath("createSession")).isEqualTo("calls/session")
        assertThat(PurrCallApi::class.java.postPath("endCall")).isEqualTo("calls/{callId}/end")
        assertThat(PurrCallApi::class.java.getPath("getCall")).isEqualTo("calls/{callId}")
        assertThat(PurrCallApi::class.java.getPath("getActiveCall")).isEqualTo("calls/active")
    }

    @Test
    fun `recording paths match the server contract`() {
        assertThat(PurrRecordingApi::class.java.getPath("getRecordings"))
            .isEqualTo("calls/{callId}/recordings")
        assertThat(PurrRecordingApi::class.java.postPath("createRecordingDownload"))
            .isEqualTo("calls/{callId}/recordings/{recordingId}/download")
        assertThat(PurrRecordingApi::class.java.getPath("getRecordingLibrary")).isEqualTo("recordings")
    }
}

private fun Class<*>.getPath(methodName: String): String = methods
    .single { it.name == methodName }
    .getAnnotation(GET::class.java)
    .value

private fun Class<*>.postPath(methodName: String): String = methods
    .single { it.name == methodName }
    .getAnnotation(POST::class.java)
    .value
