package life.fxs.purr.feature.incomingcall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.domain.account.model.IncomingCall

@Singleton
internal class AndroidIncomingCallReminder @Inject constructor(
    @ApplicationContext context: Context,
) : IncomingCallReminder {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    override fun replace(call: IncomingCall) {
        if (!canPostNotifications()) {
            dismiss()
            return
        }
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        createNotificationChannel()
        val notificationBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(appContext.getString(R.string.incoming_call_notification_title))
            .setContentText(appContext.getString(R.string.incoming_call_notification_content))
            .setWhen(call.startedAtEpochMillis)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
        if (launchIntent != null) {
            notificationBuilder.setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    REQUEST_CODE,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val notification = notificationBuilder.build()

        try {
            notificationManager.cancel(LEGACY_NOTIFICATION_ID)
            // A stable tag/id is the platform-level single-slot invariant. A new call replaces
            // the previous reminder even if a stale callback arrives during a lifecycle change.
            notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and the binder call.
        }
    }

    override fun dismiss() {
        notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        notificationManager.cancel(LEGACY_NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean =
        notificationManager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.incoming_call_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.incoming_call_notification_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "purr_incoming_call_channel"
        const val NOTIFICATION_TAG = "incoming-call"
        const val NOTIFICATION_ID = 1002
        const val LEGACY_NOTIFICATION_ID = 1002
        const val REQUEST_CODE = 1002
    }
}
