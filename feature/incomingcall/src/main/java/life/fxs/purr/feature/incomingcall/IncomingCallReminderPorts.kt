package life.fxs.purr.feature.incomingcall

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.domain.account.model.IncomingCall

internal fun interface IncomingCallSource {
    fun observe(): Flow<IncomingCall?>
}

internal fun interface IncomingCallCallerSource {
    fun observe(): Flow<PairedPartner?>
}

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
