package life.fxs.purr.feature.call

import java.time.LocalDate
import java.time.YearMonth
import life.fxs.purr.domain.call.model.CallCalendarDay

data class CallHistoryState(
    val displayedMonth: YearMonth = YearMonth.now(),
    val days: Map<LocalDate, CallCalendarDay> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface CallHistoryIntent {
    data object PreviousMonth : CallHistoryIntent
    data object NextMonth : CallHistoryIntent
    data class SelectMonth(val month: YearMonth) : CallHistoryIntent
    data object Retry : CallHistoryIntent
}
