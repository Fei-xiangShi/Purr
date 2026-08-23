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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.AndroidEntryPoint
import life.fxs.purr.MainActivity
import life.fxs.purr.R
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import life.fxs.purr.overlay.CallOverlayCoordinator
import life.fxs.purr.platform.incomingcall.CallNotificationAvatarLoader
import life.fxs.purr.core.model.CallDirection

@AndroidEntryPoint
open class CallForegroundService : Service() {
    @Inject
    lateinit var disconnectCallUseCase: DisconnectCallUseCase

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var stateStore: CallForegroundServiceStateStore

    @Inject
    lateinit var callOverlayCoordinator: CallOverlayCoordinator

    @Inject
    internal lateinit var notificationIdentitySource: CallNotificationIdentitySource

    @Inject
    internal lateinit var notificationAvatarLoader: CallNotificationAvatarLoader

    private var notificationIdentityJob: Job? = null
    private val hangUpInProgress = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            val requestedCallId = intent.getStringExtra(EXTRA_CALL_ID)
            val currentCallId = stateStore.state.value.activeCallId
            if (!CallForegroundServiceCommandPolicy.acceptsHangUp(requestedCallId, currentCallId)) {
                // A stale notification must not stop a different active call.
                if (currentCallId == null) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if (!hangUpInProgress.compareAndSet(false, true)) return START_NOT_STICKY
            applicationScope.launch {
                try {
                    disconnectCallUseCase(requireNotNull(requestedCallId))
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
        val pairId = intent.getStringExtra(EXTRA_PAIR_ID)
        if (pairId.isNullOrBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val direction = intent.getStringExtra(EXTRA_CALL_DIRECTION)
            ?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() }
        if (direction == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val notification = buildNotification(callId, pairId, direction, identity = null, callerIcon = null)
        try {
            enterForeground(notification)
        } catch (_: SecurityException) {
            handleForegroundStartFailure(callId, startId)
            return START_NOT_STICKY
        } catch (_: IllegalArgumentException) {
            handleForegroundStartFailure(callId, startId)
            return START_NOT_STICKY
        }
        stateStore.markStarted(callId, direction)
        observeNotificationIdentity(callId, pairId)
        if (::callOverlayCoordinator.isInitialized) {
            callOverlayCoordinator.start()
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
        val destroyedCallId = stateStore.state.value.activeCallId
        notificationIdentityJob?.cancel()
        notificationIdentityJob = null
        stateStore.markStopped(destroyedCallId)
        if (::callOverlayCoordinator.isInitialized) callOverlayCoordinator.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (destroyedCallId != null && !hangUpInProgress.get()) {
            applicationScope.launch {
                disconnectCallUseCase(destroyedCallId)
            }
        }
        super.onDestroy()
    }

    private fun observeNotificationIdentity(callId: String, pairId: String) {
        notificationIdentityJob?.cancel()
        notificationIdentityJob = null
        val expectedPairId = pairId
        notificationIdentityJob = applicationScope.launch {
            notificationIdentitySource.observe(expectedPairId).collectLatest { identity ->
                val callerIcon = identity?.avatarUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let { notificationAvatarLoader.load(it) }
                if (stateStore.state.value.activeCallId != callId) return@collectLatest
                val direction = stateStore.state.value.direction ?: return@collectLatest
                updateForegroundNotification(
                    buildNotification(callId, expectedPairId, direction, identity, callerIcon),
                )
            }
        }
    }

    protected open fun updateForegroundNotification(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The foreground service remains valid even if notification visibility is revoked.
        }
    }

    private fun buildNotification(
        callId: String,
        pairId: String,
        direction: CallDirection,
        identity: CallNotificationIdentity?,
        callerIcon: IconCompat?,
    ): Notification {
        val openCallIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            MainActivity.intent(this, pairId, callId, direction).setIdentifier(
                CallForegroundServiceCommandPolicy.pendingIntentIdentifier(OPEN_CALL_COMMAND, callId),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUpIntent = PendingIntent.getService(
            this,
            HANG_UP_REQUEST_CODE,
            intent(this, callId, pairId, direction)
                .setAction(ACTION_HANG_UP)
                .setIdentifier(
                    CallForegroundServiceCommandPolicy.pendingIntentIdentifier(ACTION_HANG_UP, callId),
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val callerName = identity?.displayName
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.call_notification_content)
        val caller = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .apply { callerIcon?.let(::setIcon) }
            .build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName)
            .setContentText(getString(R.string.call_notification_content))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openCallIntent)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUpIntent))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        const val EXTRA_CALL_DIRECTION = "life.fxs.purr.extra.CALL_DIRECTION"

        fun intent(context: Context, callId: String, pairId: String, direction: CallDirection): Intent =
            Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PAIR_ID, pairId)
                putExtra(EXTRA_CALL_DIRECTION, direction.name)
            }
    }
}
