package life.fxs.purr.incomingcall

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.domain.incomingcall.IncomingCallReminderContent
import life.fxs.purr.platform.incomingcall.IncomingCallUiPendingIntentFactory

@Singleton
class AppIncomingCallUiPendingIntentFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : IncomingCallUiPendingIntentFactory, IncomingCallUiLauncher {
    override fun open(content: IncomingCallReminderContent): PendingIntent =
        activityIntent(content, IncomingCallActivityContract.ACTION_SHOW)

    override fun answer(content: IncomingCallReminderContent): PendingIntent =
        activityIntent(content, IncomingCallActivityContract.ACTION_ANSWER)

    override fun launchAnswer(content: IncomingCallReminderContent) {
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        answer(content).send(context, 0, null, null, null, null, options)
    }

    private fun activityIntent(content: IncomingCallReminderContent, action: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, IncomingCallActivity::class.java).apply {
                this.action = action
                data = Uri.Builder()
                    .scheme("purr")
                    .authority("incoming-call")
                    .appendPath(content.callId)
                    .appendQueryParameter("command", action)
                    .build()
                putExtra(IncomingCallActivityContract.EXTRA_CALL_ID, content.callId)
                putExtra(IncomingCallActivityContract.EXTRA_CALLER_NAME, content.callerName)
                putExtra(IncomingCallActivityContract.EXTRA_CALLER_AVATAR_URL, content.callerAvatarUrl)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
            } else {
                null
            },
        )

    private companion object {
        const val REQUEST_CODE = 2001
    }
}
