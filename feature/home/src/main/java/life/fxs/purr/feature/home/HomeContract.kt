package life.fxs.purr.feature.home

import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.CallSessionSummary
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile

sealed interface HomeIntent {
    data object StartCall : HomeIntent
    data object RefreshStatus : HomeIntent
}

sealed interface HomeCallTarget {
    val pairId: String

    data class NewOutgoing(
        override val pairId: String,
    ) : HomeCallTarget

    data class Existing(
        override val pairId: String,
        val callId: String,
        val direction: CallDirection,
    ) : HomeCallTarget
}

data class HomeState(
    val pairId: String? = null,
    val activeCallTarget: HomeCallTarget? = null,
    val isEndingCall: Boolean = false,
    val self: SelfProfile? = null,
    val partner: PairedPartner? = null,
    val partnerPresenceOnline: Boolean? = null,
    val isCallable: Boolean = false,
    val lastSessionSummary: CallSessionSummary? = null,
    val isLoading: Boolean = false,
) {
    val hasActiveCall: Boolean
        get() = activeCallTarget != null
}

sealed interface HomeEffect {
    data class NavigateToCall(val target: HomeCallTarget) : HomeEffect
    data class ShowError(val message: String) : HomeEffect
}
