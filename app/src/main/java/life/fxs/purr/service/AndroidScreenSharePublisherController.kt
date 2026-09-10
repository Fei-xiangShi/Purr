package life.fxs.purr.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.media.screenshare.ScreenCapturePermission
import life.fxs.purr.core.media.screenshare.ScreenSharePublishRequest
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherController
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherStateStore
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherStatus
import life.fxs.purr.core.media.screenshare.requestOrNull

@Singleton
class AndroidScreenSharePublisherController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateStore: ScreenSharePublisherStateStore,
) : ScreenSharePublisherController {
    override val status = stateStore.status

    override fun prepare(request: ScreenSharePublishRequest) {
        stateStore.requestingPermission(request)
    }

    override fun start(request: ScreenSharePublishRequest, permission: ScreenCapturePermission) {
        if (status.value !is ScreenSharePublisherStatus.RequestingPermission ||
            status.value.requestOrNull() != request
        ) return
        stateStore.connecting(request)
        try {
            ContextCompat.startForegroundService(
                context,
                ScreenShareForegroundService.startIntent(context, request, permission),
            )
        } catch (error: RuntimeException) {
            stateStore.failed(request.callId, request.shareId, "无法启动屏幕共享服务，请回到通话界面重试")
        }
    }

    override fun permissionDenied(request: ScreenSharePublishRequest) {
        stateStore.failed(request.callId, request.shareId, "未授予屏幕录制权限")
    }

    override fun stop(callId: String?) {
        val currentStatus = status.value
        val request = currentStatus.requestOrNull()
        if (callId != null && request != null && request.callId != callId) return
        if (currentStatus is ScreenSharePublisherStatus.Stopping) return
        if (currentStatus is ScreenSharePublisherStatus.RequestingPermission) {
            stateStore.idle(currentStatus.request.callId, currentStatus.request.shareId)
            return
        }
        if (request == null) {
            stateStore.idle(callId)
            return
        }
        stateStore.stopping(request)
        try {
            context.startService(ScreenShareForegroundService.stopIntent(context, request.callId, request.shareId))
        } catch (error: RuntimeException) {
            context.stopService(Intent(context, ScreenShareForegroundService::class.java))
            stateStore.idle(request.callId, request.shareId)
        }
    }
}
