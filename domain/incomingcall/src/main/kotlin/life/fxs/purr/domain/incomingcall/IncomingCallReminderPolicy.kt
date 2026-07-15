package life.fxs.purr.domain.incomingcall

import javax.inject.Inject
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.domain.account.model.IncomingCall

internal class IncomingCallReminderPolicy @Inject constructor() {
    fun resolve(
        call: IncomingCall?,
        caller: PairedPartner?,
        isForeground: Boolean,
    ): IncomingCallReminderTarget =
        if (call != null && !isForeground) {
            IncomingCallReminderTarget.Visible(
                IncomingCallReminderContent(
                    callId = call.callId,
                    pairId = call.pairId,
                    callerName = caller?.displayName.takeIf { caller?.userId == call.callerUserId },
                    callerAvatarUrl = caller?.avatarUrl.takeIf { caller?.userId == call.callerUserId },
                    startedAtEpochMillis = call.startedAtEpochMillis,
                ),
            )
        } else {
            IncomingCallReminderTarget.Hidden
        }
}

internal sealed interface IncomingCallReminderTarget {
    data object Hidden : IncomingCallReminderTarget

    data class Visible(val content: IncomingCallReminderContent) : IncomingCallReminderTarget
}
