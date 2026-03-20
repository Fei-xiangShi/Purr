package life.fxs.purr.data.account.network

import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

class BearerTokenInterceptor @Inject constructor(
    private val tokenHolder: SessionTokenHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenHolder.accessToken()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
