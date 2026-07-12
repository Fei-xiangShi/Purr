package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.call.usecase.LoadCallHistoryUseCase

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val loadCallHistoryUseCase: LoadCallHistoryUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(CallHistoryState())
    val state = _state.asStateFlow()

    init {
        load(refresh = true)
    }

    fun onIntent(intent: CallHistoryIntent) {
        when (intent) {
            CallHistoryIntent.Refresh -> load(refresh = true)
            CallHistoryIntent.LoadMore -> load(refresh = false)
        }
    }

    private fun load(refresh: Boolean) {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore) return
        val cursor = if (refresh) null else current.nextCursor ?: return
        _state.value = current.copy(
            isLoading = refresh,
            isLoadingMore = !refresh,
            errorMessage = null,
        )
        viewModelScope.launch {
            when (val result = loadCallHistoryUseCase(cursor)) {
                is AppResult.Success -> {
                    val calls = if (refresh) {
                        result.value.calls
                    } else {
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
