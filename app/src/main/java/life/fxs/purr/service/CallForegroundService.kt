package life.fxs.purr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import life.fxs.purr.MainActivity
import life.fxs.purr.R
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
open class CallForegroundService : Service() {
    @Inject
    lateinit var disconnectCallUseCase: DisconnectCallUseCase

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var stateStore: CallForegroundServiceStateStore

    private var activeCallId: String? = null
    private val hangUpInProgress = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            val requestedCallId = intent.getStringExtra(EXTRA_CALL_ID)
            val currentCallId = stateStore.state.value.activeCallId ?: activeCallId
            if (!CallForegroundServiceCommandPolicy.acceptsHangUp(requestedCallId, currentCallId)) {
                // A stale notification must not stop a different active call.
                if (currentCallId == null) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if (!hangUpInProgress.compareAndSet(false, true)) return START_NOT_STICKY
            applicationScope.launch {
                try {
                    disconnectCallUseCase(requestedCallId)
                } finally {
                    hangUpInProgress.set(false)
                    stopSelfResult(startId)
                }
            }
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        if (callId.isNullOrBlank()) {
            if (!stateStore.state.value.isActive) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val existingCallId = stateStore.state.value.activeCallId
        if (existingCallId != null && existingCallId != callId) {
            // Do not replace the notification owned by the active call.
            return START_NOT_STICKY
        }
        val notification = buildNotification(callId, intent.getStringExtra(EXTRA_PAIR_ID))
        try {
            enterForeground(notification)
        } catch (_: SecurityException) {
            handleForegroundStartFailure(callId, startId)
            return START_NOT_STICKY
        } catch (_: IllegalArgumentException) {
            handleForegroundStartFailure(callId, startId)
            return START_NOT_STICKY
        }
        if (stateStore.markStarted(callId)) {
            activeCallId = callId
        }
        return START_NOT_STICKY
    }

    /** Keeps the platform call replaceable in lifecycle tests while preserving the real Service boundary. */
    protected open fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleForegroundStartFailure(callId: String, startId: Int) {
        stateStore.markStopped(callId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        // The process-local store is the source of truth when Android recreates the
        // Service object before the previous instance has published its field state.
        val destroyedCallId = stateStore.state.value.activeCallId ?: activeCallId
        stateStore.markStopped(destroyedCallId)
        activeCallId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (destroyedCallId != null && !hangUpInProgress.get()) {
            applicationScope.launch {
                disconnectCallUseCase(destroyedCallId)
            }
        }
        super.onDestroy()
    }

    private fun buildNotification(callId: String, pairId: String?): Notification {
        val openCallIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            MainActivity.intent(this, pairId).setIdentifier(
                CallForegroundServiceCommandPolicy.pendingIntentIdentifier(OPEN_CALL_COMMAND, callId),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUpIntent = PendingIntent.getService(
            this,
            HANG_UP_REQUEST_CODE,
            intent(this, callId, pairId)
                .setAction(ACTION_HANG_UP)
                .setIdentifier(
                    CallForegroundServiceCommandPolicy.pendingIntentIdentifier(ACTION_HANG_UP, callId),
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.call_notification_content))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openCallIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.call_notification_hang_up),
                    hangUpIntent,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.call_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "purr_call_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE = 1001
        private const val HANG_UP_REQUEST_CODE = 1002
        private const val OPEN_CALL_COMMAND = "open_call"
        const val ACTION_HANG_UP = "life.fxs.purr.action.HANG_UP"
        const val EXTRA_PAIR_ID = "life.fxs.purr.extra.PAIR_ID"

        const val EXTRA_CALL_ID = "life.fxs.purr.extra.CALL_ID"

        fun intent(context: Context, callId: String? = null, pairId: String? = null): Intent =
            Intent(context, CallForegroundService::class.java).apply {
                if (!callId.isNullOrBlank()) putExtra(EXTRA_CALL_ID, callId)
                if (!pairId.isNullOrBlank()) putExtra(EXTRA_PAIR_ID, pairId)
            }
    }
}
