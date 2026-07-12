package life.fxs.purr.feature.call

import life.fxs.purr.domain.call.model.CallHistoryEntry

data class CallHistoryState(
    val calls: List<CallHistoryEntry> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface CallHistoryIntent {
    data object Refresh : CallHistoryIntent
    data object LoadMore : CallHistoryIntent
}
