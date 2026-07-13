package life.fxs.purr.data.account.network

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class RefreshTokenAuthenticatorTest {
    private val tokenHolder = SessionTokenHolder()
    private val refreshCoordinator = mockk<RefreshSessionCoordinator>()
    private val authenticator = RefreshTokenAuthenticator(tokenHolder, refreshCoordinator)

    @Test
    fun `stale unauthorized response reuses current access token without refreshing again`() {
        seedSession(accessToken = "access-current", refreshToken = "refresh-current")
        val response = unauthorizedResponse(accessToken = "access-stale")

        val followUp = authenticator.authenticate(route = null, response = response)

        assertThat(followUp?.header("Authorization")).isEqualTo("Bearer access-current")
        coVerify(exactly = 0) { refreshCoordinator.refresh(any()) }
    }

    @Test
    fun `temporary refresh failure ends this retry without clearing session`() {
        seedSession(accessToken = "access-old", refreshToken = "refresh-old")
        coEvery { refreshCoordinator.refresh("refresh-old") } returns
            RefreshSessionResult.TemporarilyUnavailable

        val followUp = authenticator.authenticate(
            route = null,
            response = unauthorizedResponse(accessToken = "access-old"),
        )

        assertThat(followUp).isNull()
        assertThat(tokenHolder.refreshToken()).isEqualTo("refresh-old")
        coVerify(exactly = 1) { refreshCoordinator.refresh("refresh-old") }
    }

    @Test
    fun `successful refresh retries with the new access token`() {
        seedSession(accessToken = "access-old", refreshToken = "refresh-old")
        coEvery { refreshCoordinator.refresh("refresh-old") } returns
            RefreshSessionResult.Authenticated("access-new")

        val followUp = authenticator.authenticate(
            route = null,
            response = unauthorizedResponse(accessToken = "access-old"),
        )

        assertThat(followUp?.header("Authorization")).isEqualTo("Bearer access-new")
    }

    private fun seedSession(accessToken: String, refreshToken: String) {
        tokenHolder.update(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = "user-a",
        )
    }

    private fun unauthorizedResponse(accessToken: String): Response {
        val request = Request.Builder()
            .url("https://api.purr.test/me")
            .header("Authorization", "Bearer $accessToken")
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("unauthorized".toResponseBody())
            .build()
    }
}
