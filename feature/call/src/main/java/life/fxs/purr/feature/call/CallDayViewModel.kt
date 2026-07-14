package life.fxs.purr.feature.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.call.usecase.LoadCallHistoryUseCase

@HiltViewModel
class CallDayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadHistory: LoadCallHistoryUseCase,
) : ViewModel() {
    private val date = LocalDate.parse(requireNotNull(savedStateHandle[CALL_HISTORY_DATE_ARG]))
    private val _state = MutableStateFlow(CallDayState(date = date))
    val state = _state.asStateFlow()

    init {
        load(refresh = true)
    }

    fun onIntent(intent: CallDayIntent) {
        when (intent) {
            CallDayIntent.LoadMore -> load(refresh = false)
            CallDayIntent.Retry -> load(refresh = true)
        }
    }

    private fun load(refresh: Boolean) {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore) return
        val cursor = if (refresh) null else current.nextCursor ?: return
        val zoneId = ZoneId.systemDefault()
        val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        _state.value = current.copy(
            isLoading = refresh,
            isLoadingMore = !refresh,
            errorMessage = null,
        )
        viewModelScope.launch {
            when (val result = loadHistory(from, to, cursor)) {
                is AppResult.Success -> {
                    val calls = if (refresh) result.value.calls else {
                        (_state.value.calls + result.value.calls).distinctBy { it.callId }
                    }
                    _state.value = _state.value.copy(
                        calls = calls,
                        nextCursor = result.value.nextCursor,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = result.error.toUserMessage(),
                )
            }
        }
    }
}
