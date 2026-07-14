package life.fxs.purr.feature.incomingcall

import javax.inject.Inject
import life.fxs.purr.domain.account.model.IncomingCall

internal class IncomingCallReminderPolicy @Inject constructor() {
    fun resolve(call: IncomingCall?, isForeground: Boolean): IncomingCallReminderTarget =
        if (call != null && !isForeground) {
            IncomingCallReminderTarget.Visible(call)
        } else {
            IncomingCallReminderTarget.Hidden
        }
}

internal sealed interface IncomingCallReminderTarget {
    data object Hidden : IncomingCallReminderTarget

    data class Visible(val call: IncomingCall) : IncomingCallReminderTarget
}
