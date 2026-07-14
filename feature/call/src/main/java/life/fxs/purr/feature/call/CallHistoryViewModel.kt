package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.call.usecase.LoadCallCalendarUseCase

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val loadCalendar: LoadCallCalendarUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(CallHistoryState())
    val state = _state.asStateFlow()

    init {
        load(_state.value.displayedMonth)
    }

    fun onIntent(intent: CallHistoryIntent) {
        when (intent) {
            CallHistoryIntent.PreviousMonth -> load(_state.value.displayedMonth.minusMonths(1))
            CallHistoryIntent.NextMonth -> load(_state.value.displayedMonth.plusMonths(1))
            is CallHistoryIntent.SelectMonth -> load(intent.month)
            CallHistoryIntent.Retry -> load(_state.value.displayedMonth)
        }
    }

    private fun load(month: YearMonth) {
        if (_state.value.isLoading && month == _state.value.displayedMonth) return
        val zoneId = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        _state.value = _state.value.copy(
            displayedMonth = month,
            days = emptyMap(),
            isLoading = true,
            errorMessage = null,
        )
        viewModelScope.launch {
            when (val result = loadCalendar(from, to, zoneId.id)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    days = result.value.associateBy { LocalDate.parse(it.date) },
                    isLoading = false,
                )
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = result.error.toUserMessage(),
                )
            }
        }
    }
}
