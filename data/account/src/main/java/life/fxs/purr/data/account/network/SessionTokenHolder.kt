package life.fxs.purr.data.account.network

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionTokenHolder @Inject constructor() {
    private val accessTokenRef = AtomicReference<String?>(null)
    private val refreshTokenRef = AtomicReference<String?>(null)

    fun accessToken(): String? = accessTokenRef.get()

    fun refreshToken(): String? = refreshTokenRef.get()

    fun update(accessToken: String?, refreshToken: String?) {
        accessTokenRef.set(accessToken)
        refreshTokenRef.set(refreshToken)
    }

    fun clear() {
        update(accessToken = null, refreshToken = null)
    }
}
