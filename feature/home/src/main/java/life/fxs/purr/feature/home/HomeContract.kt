package life.fxs.purr.feature.home

import life.fxs.purr.core.model.CallSessionSummary
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.IncomingCall

sealed interface HomeIntent {
    data object StartCall : HomeIntent
    data object RefreshStatus : HomeIntent
    data object AcceptIncomingCall : HomeIntent
    data object DeclineIncomingCall : HomeIntent
}

data class HomeState(
    val pairId: String? = null,
    val self: SelfProfile? = null,
    val partner: PairedPartner? = null,
    val isCallable: Boolean = false,
    val lastSessionSummary: CallSessionSummary? = null,
    val isLoading: Boolean = false,
    val incomingCall: IncomingCall? = null,
)

sealed interface HomeEffect {
    data class NavigateToCall(val pairId: String) : HomeEffect
    data class ShowError(val message: String) : HomeEffect
}
