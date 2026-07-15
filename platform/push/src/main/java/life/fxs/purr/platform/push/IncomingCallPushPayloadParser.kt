package life.fxs.purr.platform.push

import javax.inject.Inject

internal data class IncomingCallWakeSignal(
    val callId: String,
    val startedAtEpochMillis: Long,
)

internal class IncomingCallPushPayloadParser @Inject constructor() {
    fun parse(
        data: Map<String, String>,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): IncomingCallWakeSignal? {
        if (data[KEY_TYPE] != TYPE_INCOMING_CALL) return null
        val callId = data[KEY_CALL_ID]?.takeIf(::isValidCallId) ?: return null
        val startedAt = data[KEY_STARTED_AT]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val ageMillis = nowEpochMillis - startedAt
        if (ageMillis !in -MAX_FUTURE_SKEW_MILLIS..MAX_SIGNAL_AGE_MILLIS) return null
        return IncomingCallWakeSignal(callId, startedAt)
    }

    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_CALL_ID = "callId"
        const val KEY_STARTED_AT = "startedAtEpochMillis"
        const val TYPE_INCOMING_CALL = "incoming_call"
        const val MAX_SIGNAL_AGE_MILLIS = 90_000L
        const val MAX_FUTURE_SKEW_MILLIS = 30_000L
    }
}

internal fun isValidCallId(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9._-]{1,128}"))
