package life.fxs.purr.core.network.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

class ApiContractTest {
    @Test
    fun `account and authentication paths match the server contract`() {
        assertThat(PurrAuthApi::class.java.postPath("login")).isEqualTo("auth/login")
        assertThat(PurrAuthApi::class.java.postPath("refresh")).isEqualTo("auth/refresh")
        assertThat(PurrAuthApi::class.java.postPath("logout")).isEqualTo("auth/logout")
        assertThat(PurrAccountApi::class.java.getPath("getMe")).isEqualTo("me")
        assertThat(PurrAccountApi::class.java.getPath("getPair")).isEqualTo("pair")
        assertThat(PurrAccountApi::class.java.putPath("changePassword")).isEqualTo("me/password")
        assertThat(PurrAccountApi::class.java.putPath("updateProfile")).isEqualTo("me/profile")
        assertThat(PurrAccountApi::class.java.putPath("uploadAvatar")).isEqualTo("me/avatar")
    }

    @Test
    fun `call paths match the server contract`() {
        assertThat(PurrCallApi::class.java.postPath("createSession")).isEqualTo("calls/session")
        assertThat(PurrCallApi::class.java.postPath("endCall")).isEqualTo("calls/{callId}/end")
        assertThat(PurrCallApi::class.java.getPath("getCall")).isEqualTo("calls/{callId}")
        assertThat(PurrCallApi::class.java.getPath("getActiveCall")).isEqualTo("calls/active")
        assertThat(PurrCallHistoryApi::class.java.getPath("getCalendar")).isEqualTo("calls/history/calendar")
        assertThat(PurrCallHistoryApi::class.java.getPath("getDay")).isEqualTo("calls/history/day")
        assertThat(PurrCallHistoryApi::class.java.getPath("getDetail")).isEqualTo("calls/{callId}/details")
        assertThat(PurrCallTelemetryApi::class.java.postPath("report")).isEqualTo("calls/{callId}/telemetry")
    }

    @Test
    fun `recording paths match the server contract`() {
        assertThat(PurrRecordingApi::class.java.getPath("getRecordings"))
            .isEqualTo("calls/{callId}/recordings")
        assertThat(PurrRecordingApi::class.java.postPath("createRecordingDownload"))
            .isEqualTo("calls/{callId}/recordings/{recordingId}/download")
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

private fun Class<*>.putPath(methodName: String): String = methods
    .single { it.name == methodName }
    .getAnnotation(PUT::class.java)
    .value
