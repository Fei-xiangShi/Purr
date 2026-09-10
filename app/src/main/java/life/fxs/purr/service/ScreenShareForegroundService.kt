package life.fxs.purr.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import life.fxs.purr.R
import life.fxs.purr.core.media.screenshare.ScreenCapturePermission
import life.fxs.purr.core.media.screenshare.ScreenSharePublishRequest
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherStateStore
import life.fxs.purr.core.screenpublisher.RootEncoderWhipScreenPublisher

@AndroidEntryPoint
class ScreenShareForegroundService : Service() {
    @Inject
    lateinit var stateStore: ScreenSharePublisherStateStore

    private var publisher: RootEncoderWhipScreenPublisher? = null
    private var activeRequest: ScreenSharePublishRequest? = null
    private var explicitStop = false
    private var terminalFailure = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPublishing(explicit = true)
            ACTION_START -> startPublishing(intent)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startPublishing(intent: Intent) {
        val request = intent.toPublishRequest()
        val permissionData = intent.permissionData()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (request == null || permissionData == null || resultCode != Activity.RESULT_OK) {
            fail(request, "屏幕录制授权已失效")
            return
        }
        if (System.currentTimeMillis() >= request.expiresAtEpochMillis) {
            fail(request, "投屏凭证已过期，请重新发起")
            return
        }

        // Android 14 requires the mediaProjection foreground service to be promoted before
        // exchanging the one-shot permission token for a MediaProjection instance.
        try {
            startProjectionForeground(request)
        } catch (error: SecurityException) {
            fail(request, "无法启动屏幕录制前台服务")
            return
        }

        val projection = try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            manager.getMediaProjection(resultCode, permissionData)
                ?: error("MediaProjection unavailable")
        } catch (error: SecurityException) {
            fail(request, "屏幕录制授权已失效或已被使用")
            return
        } catch (error: RuntimeException) {
            fail(request, "无法获取屏幕录制权限")
            return
        }

        stopPublisherSafely()
        activeRequest = request
        explicitStop = false
        terminalFailure = false
        stateStore.connecting(request)
        try {
            publisher = RootEncoderWhipScreenPublisher(
                applicationContext,
                projection,
                request,
                object : RootEncoderWhipScreenPublisher.Listener {
                    override fun onLive(request: ScreenSharePublishRequest) {
                        stateStore.live(request)
                    }

                    override fun onStopped(request: ScreenSharePublishRequest) {
                        if (!terminalFailure) stateStore.idle(request.callId)
                        stopSelf()
                    }

                    override fun onFailed(request: ScreenSharePublishRequest, message: String) {
                        terminalFailure = true
                        stateStore.failed(request.callId, request.shareId, message)
                        stopSelf()
                    }
                },
            )
            publisher?.start()
        } catch (error: Throwable) {
            fail(request, error.message ?: "设备无法启动屏幕和系统音频编码")
        }
    }

    private fun startProjectionForeground(request: ScreenSharePublishRequest) {
        createNotificationChannel()
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent(this, request.callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Purr 正在共享屏幕")
            .setContentText("屏幕画面与系统声音正在直播，语音通话保持连接")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "停止投屏", stopPendingIntent)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "屏幕共享",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示通话中的屏幕共享状态"
                setSound(null, null)
            },
        )
    }

    private fun stopPublishing(explicit: Boolean) {
        explicitStop = explicitStop || explicit
        activeRequest?.let(stateStore::stopping)
        stopPublisherSafely()
        activeRequest?.let { request ->
            if (!terminalFailure) stateStore.idle(request.callId)
        }
        activeRequest = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(request: ScreenSharePublishRequest?, message: String) {
        terminalFailure = true
        stateStore.failed(request?.callId, request?.shareId, message)
        stopPublisherSafely()
        activeRequest = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val request = activeRequest
        if (publisher != null && !explicitStop && !terminalFailure) {
            terminalFailure = true
            stateStore.failed(request?.callId, request?.shareId, "投屏服务被系统终止")
        }
        stopPublisherSafely()
        activeRequest = null
        super.onDestroy()
    }

    private fun stopPublisherSafely() {
        val current = publisher
        publisher = null
        try {
            current?.stop()
        } catch (_: RuntimeException) {
            // A screen-publisher failure must never terminate the LiveKit call process.
        } catch (_: LinkageError) {
            // Third-party binary mismatches are contained to screen sharing.
        }
    }

    companion object {
        private const val ACTION_START = "life.fxs.purr.action.START_SCREEN_SHARE"
        private const val ACTION_STOP = "life.fxs.purr.action.STOP_SCREEN_SHARE"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_SHARE_ID = "share_id"
        private const val EXTRA_WHIP_URL = "whip_url"
        private const val EXTRA_BEARER_TOKEN = "bearer_token"
        private const val EXTRA_EXPIRES_AT = "expires_at"
        private const val EXTRA_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PERMISSION_DATA = "projection_permission_data"
        private const val CHANNEL_ID = "purr_screen_share"
        private const val NOTIFICATION_ID = 2_403
        private const val STOP_REQUEST_CODE = 2_404

        fun startIntent(
            context: Context,
            request: ScreenSharePublishRequest,
            permission: ScreenCapturePermission,
        ): Intent = Intent(context, ScreenShareForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_CALL_ID, request.callId)
            .putExtra(EXTRA_SHARE_ID, request.shareId)
            .putExtra(EXTRA_WHIP_URL, request.whipUrl)
            .putExtra(EXTRA_BEARER_TOKEN, request.bearerToken)
            .putExtra(EXTRA_EXPIRES_AT, request.expiresAtEpochMillis)
            .putExtra(EXTRA_RESULT_CODE, permission.resultCode)
            .putExtra(EXTRA_PERMISSION_DATA, permission.data)

        fun stopIntent(context: Context, callId: String): Intent =
            Intent(context, ScreenShareForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_CALL_ID, callId)
    }

    private fun Intent.toPublishRequest(): ScreenSharePublishRequest? {
        val callId = getStringExtra(EXTRA_CALL_ID)?.takeIf(String::isNotBlank) ?: return null
        val shareId = getStringExtra(EXTRA_SHARE_ID)?.takeIf(String::isNotBlank) ?: return null
        val url = getStringExtra(EXTRA_WHIP_URL)?.takeIf(String::isNotBlank) ?: return null
        val token = getStringExtra(EXTRA_BEARER_TOKEN)?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = getLongExtra(EXTRA_EXPIRES_AT, 0L).takeIf { it > 0L } ?: return null
        return ScreenSharePublishRequest(callId, shareId, url, token, expiresAt)
    }

    @Suppress("DEPRECATION")
    private fun Intent.permissionData(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
    } else {
        getParcelableExtra(EXTRA_PERMISSION_DATA)
    }
}
