package life.fxs.purr.platform.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.incomingcall.IncomingCallRecoveryResult
import life.fxs.purr.domain.incomingcall.RecoverIncomingCallUseCase

@HiltWorker
class IncomingCallWakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val recoverIncomingCall: RecoverIncomingCallUseCase,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val callId = inputData.getString(KEY_CALL_ID)?.takeIf(::isValidCallId)
            ?: return Result.failure()
        return when (val recovered = recoverIncomingCall()) {
            IncomingCallRecoveryResult.NoAuthenticatedSession -> Result.success()
            is IncomingCallRecoveryResult.Refreshed -> when (recovered.result) {
                is AppResult.Success -> Result.success()
                is AppResult.Failure -> Result.retry()
            }
        }
    }

    /** Used by WorkManager when expedited work runs through a foreground service (API < 31). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                WAKE_CHANNEL_ID,
                context.getString(R.string.incoming_call_wake_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
        val notification = Notification.Builder(context, WAKE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(context.getString(R.string.incoming_call_wake_notification_title))
            .setOngoing(true)
            .build()
        return ForegroundInfo(WAKE_NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_CALL_ID = "call_id"
        internal const val WAKE_CHANNEL_ID = "purr_incoming_call_wake"
        internal const val WAKE_NOTIFICATION_ID = 1003
    }
}
