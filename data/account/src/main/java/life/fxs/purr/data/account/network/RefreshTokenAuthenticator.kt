package life.fxs.purr.data.account.network

import javax.inject.Inject
import javax.inject.Singleton
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

        val refreshToken = tokenHolder.refreshToken() ?: return null

        val refreshedAccessToken = runBlocking {
            refreshCoordinator.refresh(refreshToken)
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedAccessToken")
            .build()
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
}
