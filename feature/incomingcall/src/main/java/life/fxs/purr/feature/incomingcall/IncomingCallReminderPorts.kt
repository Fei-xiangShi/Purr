package life.fxs.purr.feature.incomingcall

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import life.fxs.purr.domain.account.model.IncomingCall

internal fun interface IncomingCallSource {
    fun observe(): Flow<IncomingCall?>
}

internal interface ApplicationVisibility {
    val isForeground: StateFlow<Boolean>
}

/** A single presentation slot: replacing it must never stack another reminder. */
internal interface IncomingCallReminder {
    fun replace(call: IncomingCall)

    fun dismiss()
}
