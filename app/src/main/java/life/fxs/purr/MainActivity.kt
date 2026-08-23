package life.fxs.purr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import life.fxs.purr.core.designsystem.theme.PurrTheme
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.navigation.CallNavigationRequest
import life.fxs.purr.navigation.CallNavigationRequestStore
import life.fxs.purr.navigation.PurrNavHost
import javax.inject.Inject
import life.fxs.purr.overlay.CallOverlayVisibilityStore
import life.fxs.purr.feature.call.RecordingDownloadRequest
import life.fxs.purr.feature.incomingcall.IncomingCallNavigationContract

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var callOverlayVisibilityStore: CallOverlayVisibilityStore
    private val callNavigationRequestStore = CallNavigationRequestStore()
    private var pendingCallRequest by mutableStateOf<CallNavigationRequest?>(null)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePendingCallRequest(intent)
        setContent {
            PurrTheme {
                PurrNavHost(
                    initialCallRequest = pendingCallRequest,
                    onCallRequestConsumed = ::consumeCallRequest,
                    onCallSurfaceVisibilityChanged = callOverlayVisibilityStore::setCallSurfaceVisible,
                    onRecordingDownload = ::downloadRecording,
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

    override fun onStart() {
        super.onStart()
        callOverlayVisibilityStore.setApplicationForeground(true)
    }

    override fun onStop() {
        callOverlayVisibilityStore.setApplicationForeground(false)
        super.onStop()
    }

    private fun updatePendingCallRequest(intent: Intent) {
        pendingCallRequest = callNavigationRequestStore.submit(
            intent.getStringExtra(EXTRA_CALL_PAIR_ID),
            intent.callDirection(),
            intent.getStringExtra(EXTRA_CALL_ID),
        )
    }

    private fun consumeCallRequest(requestId: Long) {
        if (callNavigationRequestStore.consume(requestId)) {
            setIntent(
                Intent(intent).apply {
                    removeExtra(EXTRA_CALL_PAIR_ID)
                    removeExtra(EXTRA_CALL_DIRECTION)
                    removeExtra(EXTRA_CALL_ID)
                },
            )
        }
        pendingCallRequest = callNavigationRequestStore.pending
    }

    private fun downloadRecording(request: RecordingDownloadRequest) {
        val uri = Uri.parse(request.url)
        if (uri.scheme !in setOf("http", "https")) {
            Toast.makeText(this, "录音下载地址无效", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(
                DownloadManager.Request(uri)
                    .setTitle("通话录音")
                    .setDescription(request.fileName)
                    .setMimeType("audio/ogg")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName),
            )
        }.onFailure {
            Toast.makeText(this, "无法开始下载录音", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_CALL_PAIR_ID = IncomingCallNavigationContract.EXTRA_CALL_PAIR_ID
        const val EXTRA_CALL_DIRECTION = IncomingCallNavigationContract.EXTRA_CALL_DIRECTION
        const val EXTRA_CALL_ID = IncomingCallNavigationContract.EXTRA_CALL_ID

        fun intent(
            context: android.content.Context,
            pairId: String,
            callId: String,
            direction: CallDirection,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALL_PAIR_ID, pairId)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALL_DIRECTION, direction.name)
            }
    }

}

private fun Intent.callDirection(): CallDirection? =
    getStringExtra(MainActivity.EXTRA_CALL_DIRECTION)
        ?.let { serialized -> runCatching { CallDirection.valueOf(serialized) }.getOrNull() }
