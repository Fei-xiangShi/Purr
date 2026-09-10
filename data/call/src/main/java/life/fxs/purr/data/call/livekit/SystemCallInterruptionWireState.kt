package life.fxs.purr.data.call.livekit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import life.fxs.purr.core.model.SystemCallInterruptionPhase

@Serializable
internal data class SystemCallInterruptionWireState(
    val v: Int,
    val callId: String,
    val senderGeneration: Long,
    val senderSessionId: String,
    val sequence: Long,
    val operationId: String,
    val phase: String,
    val degraded: Boolean,
)

internal object SystemCallInterruptionWireCodec {
    const val ATTRIBUTE_KEY = "life.fxs.purr.call.interruption"
    const val SCHEMA_VERSION = 1
    const val MAX_VALUE_LENGTH = 2_048

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(state: SystemCallInterruptionWireState): String =
        json.encodeToString(SystemCallInterruptionWireState.serializer(), state)

    fun decode(value: String): SystemCallInterruptionWireState? {
        if (value.isBlank() || value.length > MAX_VALUE_LENGTH) return null
        return runCatching {
            json.decodeFromString(SystemCallInterruptionWireState.serializer(), value)
        }.getOrNull()?.takeIf { state ->
            state.v == SCHEMA_VERSION &&
                state.callId.isNotBlank() &&
                state.senderSessionId.isNotBlank() &&
                state.operationId.isNotBlank() &&
                state.sequence >= 0L &&
                state.toPhase() != null
        }
    }
}

internal fun SystemCallInterruptionWireState.toPhase(): SystemCallInterruptionPhase? =
    when (phase) {
        "suspended" -> SystemCallInterruptionPhase.Suspended
        "resuming" -> SystemCallInterruptionPhase.Resuming
        "active" -> SystemCallInterruptionPhase.Active
        else -> null
    }

internal fun SystemCallInterruptionPhase.toWireValue(): String = when (this) {
    SystemCallInterruptionPhase.Suspended -> "suspended"
    SystemCallInterruptionPhase.Resuming -> "resuming"
    SystemCallInterruptionPhase.Active -> "active"
}
