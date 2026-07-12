package life.fxs.purr.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.usecase.CreateRecordingDownloadUseCase
import life.fxs.purr.domain.call.usecase.LoadRecordingLibraryUseCase

@HiltViewModel
class RecordingLibraryViewModel @Inject constructor(
    private val loadRecordingLibraryUseCase: LoadRecordingLibraryUseCase,
    private val createRecordingDownloadUseCase: CreateRecordingDownloadUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordingLibraryState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecordingLibraryEffect>()
    val effects = _effects.asSharedFlow()

    init {
        load(refresh = true)
    }

    fun onIntent(intent: RecordingLibraryIntent) {
        when (intent) {
            RecordingLibraryIntent.Refresh -> load(refresh = true)
            RecordingLibraryIntent.LoadMore -> load(refresh = false)
            is RecordingLibraryIntent.Play -> play(intent.recordingId)
            is RecordingLibraryIntent.PlaybackStarted -> _state.value = _state.value.copy(
                playbackLoadingRecordingId = null,
                playingRecordingId = intent.recordingId,
                errorMessage = null,
            )
            is RecordingLibraryIntent.PlaybackStopped -> if (_state.value.playingRecordingId == intent.recordingId) {
                _state.value = _state.value.copy(playingRecordingId = null)
            }
            is RecordingLibraryIntent.PlaybackFailed -> _state.value = _state.value.copy(
                playbackLoadingRecordingId = null,
                playingRecordingId = null,
                errorMessage = intent.reason ?: "录音播放失败",
            )
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
            when (val result = loadRecordingLibraryUseCase(cursor)) {
                is AppResult.Success -> {
                    val recordings = if (refresh) {
                        result.value.recordings
                    } else {
                        (_state.value.recordings + result.value.recordings).distinctBy { it.recordingId }
                    }
                    _state.value = _state.value.copy(
                        recordings = recordings,
                        nextCursor = result.value.nextCursor,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = result.error.toDisplayMessage(),
                )
            }
        }
    }

    private fun play(recordingId: String) {
        val recording = _state.value.recordings.firstOrNull { it.recordingId == recordingId } ?: return
        if (!recording.downloadAvailable) return
        if (_state.value.playingRecordingId == recordingId) {
            _state.value = _state.value.copy(playingRecordingId = null)
            viewModelScope.launch { _effects.emit(RecordingLibraryEffect.Pause) }
            return
        }
        if (_state.value.playbackLoadingRecordingId != null) return
        _state.value = _state.value.copy(playbackLoadingRecordingId = recordingId, errorMessage = null)
        viewModelScope.launch {
            when (val result = createRecordingDownloadUseCase(recording.callId, recordingId)) {
                is AppResult.Success -> _effects.emit(
                    RecordingLibraryEffect.Play(recordingId, result.value.url),
                )
                is AppResult.Failure -> _state.value = _state.value.copy(
                    playbackLoadingRecordingId = null,
                    errorMessage = result.error.toDisplayMessage(),
                )
            }
        }
    }
}

private fun AppError.toDisplayMessage(): String = when (this) {
    is AppError.Network -> message ?: "网络错误"
    is AppError.Unauthorized -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "发生未知错误"
}
