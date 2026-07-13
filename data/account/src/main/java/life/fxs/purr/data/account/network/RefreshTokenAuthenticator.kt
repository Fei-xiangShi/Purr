package life.fxs.purr.data.account.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

@Singleton
class RefreshTokenAuthenticator @Inject constructor(
    private val tokenHolder: SessionTokenHolder,
    private val refreshCoordinator: RefreshSessionCoordinator,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        if (response.request.url.encodedPath.startsWith("/auth/")) return null

        val rejectedAccessToken = response.request.bearerToken() ?: return null
        val currentSession = tokenHolder.snapshot()
        val currentAccessToken = currentSession.accessToken ?: return null
        if (currentAccessToken != rejectedAccessToken) {
            return response.request.withAccessToken(currentAccessToken)
        }

        val refreshToken = currentSession.refreshToken ?: return null

        val refreshResult = try {
            runBlocking { refreshCoordinator.refresh(refreshToken) }
        } catch (_: CancellationException) {
            return null
        }

        val refreshedAccessToken = (refreshResult as? RefreshSessionResult.Authenticated)
            ?.accessToken
            ?: return null
        if (refreshedAccessToken == rejectedAccessToken) return null

        return response.request.withAccessToken(refreshedAccessToken)
    }

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 1
        while (current?.priorResponse != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private fun Request.bearerToken(): String? {
        val authorization = header(AUTHORIZATION_HEADER) ?: return null
        if (!authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return authorization.substring(BEARER_PREFIX.length).trim().ifBlank { null }
    }

    private fun Request.withAccessToken(accessToken: String): Request = newBuilder()
        .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$accessToken")
        .build()

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
