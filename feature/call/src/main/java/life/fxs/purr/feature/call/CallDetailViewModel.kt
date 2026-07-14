package life.fxs.purr.feature.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.call.usecase.CreateRecordingDownloadUseCase
import life.fxs.purr.domain.call.usecase.LoadCallDetailUseCase
import life.fxs.purr.domain.call.usecase.LoadCallRecordingsUseCase
import life.fxs.purr.domain.call.usecase.LoadCallTranscriptUseCase

@HiltViewModel
class CallDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadDetail: LoadCallDetailUseCase,
    private val loadRecordings: LoadCallRecordingsUseCase,
    private val loadTranscript: LoadCallTranscriptUseCase,
    private val createDownload: CreateRecordingDownloadUseCase,
) : ViewModel() {
    private val callId: String = requireNotNull(savedStateHandle[CALL_HISTORY_CALL_ID_ARG])
    private val _state = MutableStateFlow(CallDetailState(callId = callId))
    val state = _state.asStateFlow()
    private val _effects = Channel<CallDetailEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        load()
    }

    fun onIntent(intent: CallDetailIntent) {
        when (intent) {
            CallDetailIntent.Retry -> load()
            is CallDetailIntent.DownloadRecording -> download(intent.recordingId)
        }
    }

    private fun load() {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val detail = async { loadDetail(callId) }
            val recordings = async { loadRecordings(callId) }
            val transcript = async { loadTranscript(callId) }
            val detailResult = detail.await()
            val recordingResult = recordings.await()
            val transcriptResult = transcript.await()
            val firstFailure = listOf(detailResult, recordingResult, transcriptResult)
                .filterIsInstance<AppResult.Failure>()
                .firstOrNull()
            _state.value = _state.value.copy(
                detail = (detailResult as? AppResult.Success)?.value,
                recordings = (recordingResult as? AppResult.Success)?.value.orEmpty(),
                transcript = (transcriptResult as? AppResult.Success)?.value,
                isLoading = false,
                errorMessage = firstFailure?.error?.toUserMessage(),
            )
        }
    }

    private fun download(recordingId: String) {
        if (recordingId in _state.value.downloadingRecordingIds) return
        _state.value = _state.value.copy(
            downloadingRecordingIds = _state.value.downloadingRecordingIds + recordingId,
            errorMessage = null,
        )
        viewModelScope.launch {
            when (val result = createDownload(callId, recordingId)) {
                is AppResult.Success -> _effects.send(
                    CallDetailEffect.DownloadReady(
                        url = result.value.url,
                        fileName = "purr-call-${callId.safeFileComponent()}-${recordingId.safeFileComponent()}.ogg",
                    ),
                )
                is AppResult.Failure -> _state.value = _state.value.copy(
                    errorMessage = result.error.toUserMessage(),
                )
            }
            _state.value = _state.value.copy(
                downloadingRecordingIds = _state.value.downloadingRecordingIds - recordingId,
            )
        }
    }
}

private fun String.safeFileComponent(): String =
    take(32).replace(Regex("[^A-Za-z0-9._-]"), "_")
