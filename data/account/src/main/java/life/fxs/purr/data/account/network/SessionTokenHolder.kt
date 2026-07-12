package life.fxs.purr.data.account.network

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionTokenHolder @Inject constructor() {
    private val accessTokenRef = AtomicReference<String?>(null)
    private val refreshTokenRef = AtomicReference<String?>(null)
    private val userIdRef = AtomicReference<String?>(null)

    fun accessToken(): String? = accessTokenRef.get()

    fun refreshToken(): String? = refreshTokenRef.get()

    fun userId(): String? = userIdRef.get()

    fun update(accessToken: String?, refreshToken: String?, userId: String?) {
        accessTokenRef.set(accessToken)
        refreshTokenRef.set(refreshToken)
        userIdRef.set(userId)
    }

    fun clear() {
        update(accessToken = null, refreshToken = null, userId = null)
    }
}
