package life.fxs.purr.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.BufferOverflow
import life.fxs.purr.core.common.NetworkAvailability

/** Default-network callback flow; each collector owns its own registration. */
@Singleton
class AndroidNetworkAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkAvailability {
    override val available: Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            close()
            return@callbackFlow
        }
        val ignoredNetwork = AtomicReference<Network?>(runCatching { manager.activeNetwork }.getOrNull())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (ignoredNetwork.compareAndSet(network, null)) return
                ignoredNetwork.set(null)
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                ignoredNetwork.compareAndSet(network, null)
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { close(it) }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.buffer(1, BufferOverflow.DROP_OLDEST)
}
