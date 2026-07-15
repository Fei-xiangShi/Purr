package life.fxs.purr.platform.telecom

import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

internal data class TelecomCallCallbacks(
    val onAnswer: suspend (Int) -> Unit,
    val onDisconnect: suspend (DisconnectCause) -> Unit,
    val onSetActive: suspend () -> Unit,
    val onSetInactive: suspend () -> Unit,
)

internal interface TelecomCallGateway {
    fun registerApp()

    suspend fun addCall(
        attributes: CallAttributesCompat,
        callbacks: TelecomCallCallbacks,
        onReady: (CallControlScope) -> Unit,
    )
}

@Singleton
internal class AndroidXTelecomCallGateway @Inject constructor(
    private val callsManager: CallsManager,
) : TelecomCallGateway {
    private val registered = AtomicBoolean(false)

    override fun registerApp() {
        if (!registered.compareAndSet(false, true)) return
        try {
            callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        } catch (error: Throwable) {
            registered.set(false)
            throw error
        }
    }

    override suspend fun addCall(
        attributes: CallAttributesCompat,
        callbacks: TelecomCallCallbacks,
        onReady: (CallControlScope) -> Unit,
    ) {
        callsManager.addCall(
            callAttributes = attributes,
            onAnswer = callbacks.onAnswer,
            onDisconnect = callbacks.onDisconnect,
            onSetActive = callbacks.onSetActive,
            onSetInactive = callbacks.onSetInactive,
            block = onReady,
        )
    }
}
