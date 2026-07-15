package life.fxs.purr.domain.incomingcall

import kotlinx.coroutines.flow.StateFlow

interface ApplicationVisibility {
    val isForeground: StateFlow<Boolean>
}

/** A single presentation slot: replacing it must never stack another reminder. */
interface IncomingCallReminder {
    fun replace(content: IncomingCallReminderContent)

    fun dismiss()
}

data class IncomingCallReminderContent(
    val callId: String,
    val pairId: String,
    val callerName: String?,
    val callerAvatarUrl: String?,
    val startedAtEpochMillis: Long,
)
