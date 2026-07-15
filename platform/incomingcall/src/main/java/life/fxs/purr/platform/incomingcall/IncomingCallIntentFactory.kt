package life.fxs.purr.platform.incomingcall

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import life.fxs.purr.feature.incomingcall.IncomingCallReminderContent

internal class IncomingCallIntentFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun open(content: IncomingCallReminderContent): PendingIntent = activityIntent(content, ACTION_SHOW)

    fun answer(content: IncomingCallReminderContent): PendingIntent = activityIntent(content, ACTION_ANSWER)

    fun decline(content: IncomingCallReminderContent): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, IncomingCallDeclineReceiver::class.java).apply {
            action = ACTION_DECLINE
            data = callUri(content.callId, ACTION_DECLINE)
            putExtra(EXTRA_CALL_ID, content.callId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun activityIntent(content: IncomingCallReminderContent, intentAction: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, IncomingCallActivity::class.java).apply {
                action = intentAction
                data = callUri(content.callId, intentAction)
                putExtra(EXTRA_CALL_ID, content.callId)
                putExtra(EXTRA_CALLER_NAME, content.callerName)
                putExtra(EXTRA_CALLER_AVATAR_URL, content.callerAvatarUrl)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun callUri(callId: String, command: String): Uri = Uri.Builder()
        .scheme(URI_SCHEME)
        .authority(URI_AUTHORITY)
        .appendPath(callId)
        .appendQueryParameter(QUERY_COMMAND, command)
        .build()

    companion object {
        const val ACTION_SHOW = "life.fxs.purr.action.SHOW_INCOMING_CALL"
        const val ACTION_ANSWER = "life.fxs.purr.action.ANSWER_INCOMING_CALL"
        const val ACTION_DECLINE = "life.fxs.purr.action.DECLINE_INCOMING_CALL"
        const val EXTRA_CALL_ID = "life.fxs.purr.extra.INCOMING_CALL_ID"
        const val EXTRA_CALLER_NAME = "life.fxs.purr.extra.INCOMING_CALLER_NAME"
        const val EXTRA_CALLER_AVATAR_URL = "life.fxs.purr.extra.INCOMING_CALLER_AVATAR_URL"

        private const val URI_SCHEME = "purr"
        private const val URI_AUTHORITY = "incoming-call"
        private const val QUERY_COMMAND = "command"
        private const val REQUEST_CODE = 2001
    }
}
