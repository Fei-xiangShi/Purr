package life.fxs.purr.data.account.network

import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicReference

@Singleton
class SessionTokenHolder @Inject constructor() {
    private val sessionRef = AtomicReference(SessionSnapshot())

    fun accessToken(): String? = snapshot().accessToken

    fun refreshToken(): String? = snapshot().refreshToken

    fun userId(): String? = snapshot().userId

    internal fun snapshot(): SessionSnapshot = sessionRef.get()

    fun update(accessToken: String?, refreshToken: String?, userId: String?) {
        sessionRef.set(
            SessionSnapshot(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = userId,
            ),
        )
    }

    fun clear() {
        sessionRef.set(SessionSnapshot())
    }

    internal data class SessionSnapshot(
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val userId: String? = null,
    )
}
