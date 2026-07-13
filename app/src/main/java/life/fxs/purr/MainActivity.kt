package life.fxs.purr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import life.fxs.purr.core.designsystem.theme.PurrTheme
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.navigation.CallNavigationRequest
import life.fxs.purr.navigation.CallNavigationRequestStore
import life.fxs.purr.navigation.PurrNavHost
import life.fxs.purr.service.IncomingCallNotificationManager

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var observeRealtimeStateUseCase: ObserveRealtimeStateUseCase

    private lateinit var incomingCallNotificationManager: IncomingCallNotificationManager
    private var incomingCall: IncomingCall? = null
    private var isInForeground = false
    private val callNavigationRequestStore = CallNavigationRequestStore()
    private var pendingCallRequest by mutableStateOf<CallNavigationRequest?>(null)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePendingCallRequest(intent)
        incomingCallNotificationManager = IncomingCallNotificationManager(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                observeRealtimeStateUseCase().collect { state ->
                    incomingCall = state.incomingCall
                    if (isInForeground || incomingCall == null) {
                        incomingCallNotificationManager.cancel()
                    } else {
                        incomingCallNotificationManager.show()
                    }
                }
            }
        }
        setContent {
            PurrTheme {
                PurrNavHost(
                    initialCallPairId = pendingCallRequest?.pairId,
                    initialCallRequestId = pendingCallRequest?.requestId,
                    onCallRequestConsumed = ::consumeCallRequest,
                )
            }
        }
        if (
            savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updatePendingCallRequest(intent)
    }

    private fun updatePendingCallRequest(intent: Intent) {
        pendingCallRequest = callNavigationRequestStore.submit(
            intent.getStringExtra(EXTRA_CALL_PAIR_ID),
        )
    }

    private fun consumeCallRequest(requestId: Long) {
        if (callNavigationRequestStore.consume(requestId)) {
            setIntent(Intent(intent).apply { removeExtra(EXTRA_CALL_PAIR_ID) })
        }
        pendingCallRequest = callNavigationRequestStore.pending
    }

    companion object {
        const val EXTRA_CALL_PAIR_ID = "life.fxs.purr.extra.CALL_PAIR_ID"

        fun intent(context: android.content.Context, pairId: String?): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!pairId.isNullOrBlank()) putExtra(EXTRA_CALL_PAIR_ID, pairId)
            }
    }

    override fun onStart() {
        super.onStart()
        isInForeground = true
        if (::incomingCallNotificationManager.isInitialized) {
            incomingCallNotificationManager.cancel()
        }
    }

    override fun onStop() {
        isInForeground = false
        if (
            !isChangingConfigurations &&
            ::incomingCallNotificationManager.isInitialized &&
            incomingCall != null
        ) {
            incomingCallNotificationManager.show()
        }
        super.onStop()
    }
}
