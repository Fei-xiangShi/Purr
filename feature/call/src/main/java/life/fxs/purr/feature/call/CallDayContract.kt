package life.fxs.purr.feature.call

import java.time.LocalDate
import life.fxs.purr.domain.call.model.CallHistoryEntry

data class CallDayState(
    val date: LocalDate,
    val calls: List<CallHistoryEntry> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface CallDayIntent {
    data object LoadMore : CallDayIntent
    data object Retry : CallDayIntent
}
