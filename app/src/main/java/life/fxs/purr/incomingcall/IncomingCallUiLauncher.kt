package life.fxs.purr.incomingcall

import life.fxs.purr.domain.incomingcall.IncomingCallReminderContent

/** Application-owned port for bringing an accepted incoming call into the foreground. */
interface IncomingCallUiLauncher {
    fun launchAnswer(content: IncomingCallReminderContent)
}
