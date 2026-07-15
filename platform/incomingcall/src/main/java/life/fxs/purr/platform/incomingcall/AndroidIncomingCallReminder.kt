package life.fxs.purr.platform.incomingcall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.feature.incomingcall.IncomingCallReminder
import life.fxs.purr.feature.incomingcall.IncomingCallReminderContent

@Singleton
internal class AndroidIncomingCallReminder @Inject constructor(
    @ApplicationContext context: Context,
    private val intentFactory: IncomingCallIntentFactory,
    private val fullScreenIntentCapability: FullScreenIntentCapability,
) : IncomingCallReminder {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    override fun replace(content: IncomingCallReminderContent) {
        if (!canPostNotifications()) {
            dismiss()
            return
        }

        createNotificationChannel()
        val openIntent = intentFactory.open(content)
        val callerName = content.callerName
            ?.takeIf(String::isNotBlank)
            ?: appContext.getString(R.string.incoming_call_unknown_caller)
        val notificationBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName)
            .setContentText(appContext.getString(R.string.incoming_call_notification_content))
            .setWhen(content.startedAtEpochMillis)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    Person.Builder()
                        .setName(callerName)
                        .setImportant(true)
                        .build(),
                    intentFactory.decline(content),
                    intentFactory.answer(content),
                ),
            )
        if (fullScreenIntentCapability.canUse()) {
            notificationBuilder.setFullScreenIntent(openIntent, true)
        }

        try {
            notificationManager.cancel(LEGACY_NOTIFICATION_ID)
            notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notificationBuilder.build())
        } catch (_: SecurityException) {
            // Permission may be revoked between the explicit check and the binder call.
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
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.incoming_call_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.incoming_call_notification_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(Settings.System.DEFAULT_RINGTONE_URI, audioAttributes)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "purr_incoming_call_channel_v2"
        const val NOTIFICATION_TAG = "incoming-call"
        const val NOTIFICATION_ID = 1002
        const val LEGACY_NOTIFICATION_ID = 1002
    }
}
