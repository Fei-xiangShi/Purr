package life.fxs.purr.feature.call

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallRecordingStatus

@Composable
fun RecordingLibraryScreenRoute(
    onBack: () -> Unit,
    viewModel: RecordingLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
        }
    }
    DisposableEffect(player, viewModel) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let {
                        viewModel.onIntent(RecordingLibraryIntent.PlaybackStarted(it))
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let {
                        viewModel.onIntent(RecordingLibraryIntent.PlaybackStopped(it))
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.onIntent(
                    RecordingLibraryIntent.PlaybackFailed(player.currentMediaItem?.mediaId.orEmpty(), error.message),
                )
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(viewModel, player) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecordingLibraryEffect.Play -> {
                    player.setMediaItem(
                        MediaItem.Builder().setMediaId(effect.recordingId).setUri(effect.url).build(),
                    )
                    player.prepare()
                    player.play()
                }
                RecordingLibraryEffect.Pause -> player.pause()
            }
        }
    }

    PurrScreen {
        PurrSectionTitle(eyebrow = "录音", title = "录音库", subtitle = "")
        PurrPanel(title = "历史录音") {
            PurrSecondaryButton(
                text = if (state.isLoading) "加载中..." else "刷新",
                onClick = { viewModel.onIntent(RecordingLibraryIntent.Refresh) },
                enabled = !state.isLoading && !state.isLoadingMore,
            )
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (!state.isLoading && state.recordings.isEmpty()) {
                Text("暂无录音", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.recordings.forEachIndexed { index, recording ->
                PurrStatusChip(
                    label = "录音 ${index + 1}",
                    detail = recording.libraryDetail(),
                    accentColor = when (recording.status) {
                        CallRecordingStatus.Available -> MaterialTheme.colorScheme.tertiary
                        CallRecordingStatus.Expired -> MaterialTheme.colorScheme.secondary
                        CallRecordingStatus.Processing -> MaterialTheme.colorScheme.primary
                        CallRecordingStatus.Failed -> MaterialTheme.colorScheme.error
                    },
                )
                if (recording.downloadAvailable) {
                    val loading = state.playbackLoadingRecordingId == recording.recordingId
                    val playing = state.playingRecordingId == recording.recordingId
                    PurrSecondaryButton(
                        text = when {
                            loading -> "加载中..."
                            playing -> "暂停"
                            else -> "播放"
                        },
                        onClick = { viewModel.onIntent(RecordingLibraryIntent.Play(recording.recordingId)) },
                        enabled = state.playbackLoadingRecordingId == null,
                    )
                }
            }
            if (state.nextCursor != null) {
                PurrSecondaryButton(
                    text = if (state.isLoadingMore) "加载中..." else "加载更多",
                    onClick = { viewModel.onIntent(RecordingLibraryIntent.LoadMore) },
                    enabled = !state.isLoading && !state.isLoadingMore,
                )
            }
        }
        PurrPrimaryButton(text = "返回", onClick = onBack)
    }
}

private fun CallRecording.libraryDetail(): String = when (status) {
    CallRecordingStatus.Available -> durationMillis?.let { "可播放 · ${it.libraryDuration()}" } ?: "可播放"
    CallRecordingStatus.Expired -> "已过期"
    CallRecordingStatus.Processing -> "处理中"
    CallRecordingStatus.Failed -> failureReason ?: "录音失败"
}

private fun Long.libraryDuration(): String {
    val totalSeconds = this / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
