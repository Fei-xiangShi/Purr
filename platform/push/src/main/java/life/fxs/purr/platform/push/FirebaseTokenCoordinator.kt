package life.fxs.purr.platform.push

import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import life.fxs.purr.core.common.ApplicationScope

internal interface PushTokenProvider {
    suspend fun requestRegistration(): Boolean
}

@Singleton
internal class FirebasePushTokenProvider @Inject constructor(
    private val runtime: PushRuntime,
) : PushTokenProvider {
    override suspend fun requestRegistration(): Boolean {
        if (!runtime.initialize()) return false
        return FirebaseMessaging.getInstance().register().awaitSuccess()
    }

    private suspend fun Task<Void>.awaitSuccess(): Boolean = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { completed ->
            if (!continuation.isActive) return@addOnCompleteListener
            continuation.resume(completed.isSuccessful)
        }
    }
}

@Singleton
class FirebaseTokenCoordinator @Inject internal constructor(
    private val provider: PushTokenProvider,
    private val store: PushTokenStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
            while (isActive) {
                if (provider.requestRegistration()) return@launch
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
        }
    }

    fun onRegistered(token: String) {
        store.update(token)
    }

    private companion object {
        const val INITIAL_RETRY_DELAY_MILLIS = 2_000L
        const val MAX_RETRY_DELAY_MILLIS = 60_000L
    }
}
