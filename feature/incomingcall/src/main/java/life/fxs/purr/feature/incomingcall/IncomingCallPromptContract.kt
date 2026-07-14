package life.fxs.purr.feature.incomingcall

import life.fxs.purr.domain.account.model.IncomingCall

internal sealed interface IncomingCallPromptIntent {
    data object Accept : IncomingCallPromptIntent

    data object Decline : IncomingCallPromptIntent
}

internal data class IncomingCallPromptState(
    val call: IncomingCall? = null,
    val isResponding: Boolean = false,
    val isReady: Boolean = false,
)

internal sealed interface IncomingCallPromptEffect {
    data class NavigateToCall(val pairId: String) : IncomingCallPromptEffect

    data class ShowError(val message: String) : IncomingCallPromptEffect
}
