package life.fxs.purr.feature.home

import life.fxs.purr.core.model.CallSessionSummary
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile

sealed interface HomeIntent {
    data object StartCall : HomeIntent
    data object RefreshStatus : HomeIntent
}

data class HomeState(
    val pairId: String? = null,
    val self: SelfProfile? = null,
    val partner: PairedPartner? = null,
    val isCallable: Boolean = false,
    val lastSessionSummary: CallSessionSummary? = null,
    val isLoading: Boolean = false,
)

sealed interface HomeEffect {
    data class NavigateToCall(val pairId: String) : HomeEffect
    data class ShowError(val message: String) : HomeEffect
}
