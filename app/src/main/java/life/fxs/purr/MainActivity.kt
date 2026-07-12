package life.fxs.purr

import android.Manifest
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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import life.fxs.purr.core.designsystem.theme.PurrTheme
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.navigation.PurrNavHost
import life.fxs.purr.service.IncomingCallNotificationManager

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var callRoomStateProvider: CallRoomStateProvider

    @Inject
    lateinit var observeRealtimeStateUseCase: ObserveRealtimeStateUseCase

    private lateinit var incomingCallNotificationManager: IncomingCallNotificationManager
    private var incomingCall: IncomingCall? = null
    private var isInForeground = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                PurrNavHost(roomStateProvider = callRoomStateProvider)
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
