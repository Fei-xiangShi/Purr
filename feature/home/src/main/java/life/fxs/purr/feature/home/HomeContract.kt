package life.fxs.purr.feature.home

import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.CallSessionSummary
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile

sealed interface HomeIntent {
    data object StartCall : HomeIntent
    data object RefreshStatus : HomeIntent
}

data class HomeCallTarget(
    val pairId: String,
    val direction: CallDirection = CallDirection.Outgoing,
    val expectedCallId: String? = null,
)

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
