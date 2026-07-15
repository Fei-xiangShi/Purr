package life.fxs.purr.platform.incomingcall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase

@AndroidEntryPoint
internal class IncomingCallDeclineReceiver : BroadcastReceiver() {
    @Inject
    lateinit var declineIncomingCall: DeclineIncomingCallUseCase

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingCallIntentFactory.ACTION_DECLINE) return
        val callId = intent.getStringExtra(IncomingCallIntentFactory.EXTRA_CALL_ID)
            ?.takeIf(String::isNotBlank)
            ?: return
        if (!inFlightCallIds.add(callId)) return

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                declineIncomingCall(callId)
            } finally {
                inFlightCallIds.remove(callId)
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val inFlightCallIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
