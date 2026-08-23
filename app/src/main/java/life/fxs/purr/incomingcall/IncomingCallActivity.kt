package life.fxs.purr.incomingcall

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import life.fxs.purr.R
import life.fxs.purr.core.designsystem.theme.PurrTheme
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.incomingcall.RecoverIncomingCallUseCase
import life.fxs.purr.feature.incomingcall.IncomingCallNavigationContract
import life.fxs.purr.feature.incomingcall.IncomingCallPromptRoute

@AndroidEntryPoint
internal class IncomingCallActivity : ComponentActivity() {
    @Inject
    lateinit var recoverIncomingCall: RecoverIncomingCallUseCase

    private var request: IncomingCallActivityRequest by mutableStateOf(IncomingCallActivityRequest.Invalid)
    private var recoveryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        updateRequest(intent)

        setContent {
            PurrTheme {
                when (val currentRequest = request) {
                    IncomingCallActivityRequest.Invalid -> LaunchedEffect(Unit) { finishSafely() }
                    is IncomingCallActivityRequest.Valid -> IncomingCallPromptRoute(
                        partnerName = currentRequest.callerName
                            ?: getString(R.string.incoming_call_unknown_caller),
                        partnerAvatarUrl = currentRequest.callerAvatarUrl,
                        callId = currentRequest.callId,
                        acceptImmediately = currentRequest.acceptImmediately,
                        onOpenCall = ::openActiveCall,
                        onDismiss = ::finishSafely,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateRequest(intent)
    }

    private fun updateRequest(intent: Intent?) {
        request = IncomingCallActivityRequest.from(intent)
        recoveryJob?.cancel()
        if (request is IncomingCallActivityRequest.Valid) {
            recoveryJob = lifecycleScope.launch { recoverIncomingCall() }
        }
    }

    private fun openActiveCall(pairId: String, callId: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: run {
            finishSafely()
            return
        }
        startActivity(
            launchIntent.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(IncomingCallNavigationContract.EXTRA_CALL_PAIR_ID, pairId)
                putExtra(IncomingCallNavigationContract.EXTRA_CALL_DIRECTION, CallDirection.Incoming.name)
                putExtra(IncomingCallNavigationContract.EXTRA_CALL_ID, callId)
            },
        )
        finishSafely()
    }

    private fun finishSafely() {
        if (!isFinishing) finish()
    }
}

internal object IncomingCallActivityContract {
    const val ACTION_SHOW = "life.fxs.purr.action.SHOW_INCOMING_CALL"
    const val ACTION_ANSWER = "life.fxs.purr.action.ANSWER_INCOMING_CALL"
    const val EXTRA_CALL_ID = "life.fxs.purr.extra.INCOMING_CALL_ID"
    const val EXTRA_CALLER_NAME = "life.fxs.purr.extra.INCOMING_CALLER_NAME"
    const val EXTRA_CALLER_AVATAR_URL = "life.fxs.purr.extra.INCOMING_CALLER_AVATAR_URL"
}

internal sealed interface IncomingCallActivityRequest {
    data object Invalid : IncomingCallActivityRequest

    data class Valid(
        val callId: String,
        val callerName: String?,
        val callerAvatarUrl: String?,
        val acceptImmediately: Boolean,
    ) : IncomingCallActivityRequest

    companion object {
        fun from(intent: Intent?): IncomingCallActivityRequest {
            val callId = intent?.getStringExtra(IncomingCallActivityContract.EXTRA_CALL_ID)
                ?.takeIf(String::isNotBlank)
                ?: return Invalid
            val action = intent.action
            if (action != IncomingCallActivityContract.ACTION_SHOW &&
                action != IncomingCallActivityContract.ACTION_ANSWER
            ) {
                return Invalid
            }
            return Valid(
                callId = callId,
                callerName = intent.getStringExtra(IncomingCallActivityContract.EXTRA_CALLER_NAME)
                    ?.takeIf(String::isNotBlank),
                callerAvatarUrl = intent.getStringExtra(IncomingCallActivityContract.EXTRA_CALLER_AVATAR_URL)
                    ?.takeIf(String::isNotBlank),
                acceptImmediately = action == IncomingCallActivityContract.ACTION_ANSWER,
            )
        }
    }
}
