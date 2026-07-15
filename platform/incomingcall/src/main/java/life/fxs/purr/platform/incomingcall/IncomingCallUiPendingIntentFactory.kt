package life.fxs.purr.platform.incomingcall

import android.app.PendingIntent
import life.fxs.purr.domain.incomingcall.IncomingCallReminderContent

interface IncomingCallUiPendingIntentFactory {
    fun open(content: IncomingCallReminderContent): PendingIntent

    fun answer(content: IncomingCallReminderContent): PendingIntent
}
