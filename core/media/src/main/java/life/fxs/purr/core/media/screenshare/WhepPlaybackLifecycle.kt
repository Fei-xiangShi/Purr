package life.fxs.purr.core.media.screenshare

import java.io.Closeable
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal interface WhepSession : Closeable {
    suspend fun connect()
}

internal interface WhepSessionListener {
    fun onQuality(sample: ScreenShareQualitySample) = Unit
    fun onBuffering()
    /** Returns whether the frame still belongs to the visible playback attempt. */
    fun onFrame(width: Int, height: Int): Boolean
    fun onFailed(message: String, code: ScreenShareFailureCode = ScreenShareFailureCode.Playback)
}

/** Owns attempts by identity, including reconnects using the same credentials. */
internal class WhepPlaybackLifecycle(
    private val scope: CoroutineScope,
    private val diagnostics: ScreenShareDiagnosticsStore = ScreenShareDiagnosticsStore(),
    private val failures: ScreenShareFailureReporter = NoOpScreenShareFailureReporter,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val factory: (WhepPlaybackRequest, WhepSessionListener) -> WhepSession,
) {
    private val mutableStatus = MutableStateFlow<WhepPlaybackStatus>(WhepPlaybackStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private var active: Attempt? = null

    @Synchronized
    fun start(request: WhepPlaybackRequest) {
        if (active?.request == request) return
        retireActive()
        val attempt = Attempt(request, diagnostics.beginReceiving(request.callId, request.shareId))
        active = attempt
        mutableStatus.value = WhepPlaybackStatus.Connecting(request)
        attempt.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val session = factory(request, object : WhepSessionListener {
                    override fun onQuality(sample: ScreenShareQualitySample) {
                        diagnostics.receive(attempt.epoch, sample)
                    }
                    override fun onBuffering() {
                        buffering(attempt)
                    }

                    override fun onFrame(width: Int, height: Int): Boolean = frame(attempt, width, height)

                    override fun onFailed(message: String, code: ScreenShareFailureCode) = fail(attempt, message, code)
                })
                attempt.session = session
                withTimeout(40_000L) { session.connect() }
                // Keep the watchdog under the same generation as setup and native resources.
                while (isActive) {
                    delay(250L)
                    checkFrameProgress(attempt)
                }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) fail(attempt, "共享画面连接超时", ScreenShareFailureCode.ConnectTimeout)
                else throw error
            } catch (error: Throwable) {
                fail(attempt, error.message?.takeIf(String::isNotBlank) ?: "共享画面连接失败",
                    (error as? ScreenShareFailureException)?.code ?: ScreenShareFailureCode.Playback)
            }
        }
        attempt.job.start()
    }

    @Synchronized
    fun stop(shareId: String?) {
        val current = active?.request
        if (shareId != null && current?.shareId != shareId) return
        retireActive()
        mutableStatus.value = WhepPlaybackStatus.Stopped(shareId ?: current?.shareId)
    }

    @Synchronized
    private fun buffering(attempt: Attempt) {
        if (active !== attempt) return
        attempt.recoveryStartedAt = null
        mutableStatus.value = WhepPlaybackStatus.Buffering(attempt.request)
    }

    @Synchronized
    private fun frame(attempt: Attempt, width: Int, height: Int): Boolean {
        if (active !== attempt) return false
        val now = clock()
        val previousFrameAt = attempt.lastFrameAt
        attempt.lastFrameAt = now
        // One isolated frame must not flash Live during an ongoing network stall.
        if (previousFrameAt == null || now - previousFrameAt >= FRAME_STALL_MILLIS) {
            attempt.recoveryStartedAt = now
            mutableStatus.value = WhepPlaybackStatus.Buffering(attempt.request)
        }
        if (mutableStatus.value is WhepPlaybackStatus.Buffering) {
            val recoveryStarted = attempt.recoveryStartedAt
            if (recoveryStarted == null) attempt.recoveryStartedAt = now
            else if (now - recoveryStarted >= RECOVERY_MILLIS) {
                mutableStatus.value = WhepPlaybackStatus.Live(attempt.request, width, height)
            }
        } else {
            mutableStatus.value = WhepPlaybackStatus.Live(attempt.request, width, height)
        }
        return true
    }

    @Synchronized
    private fun checkFrameProgress(attempt: Attempt) {
        if (active !== attempt) return
        val lastFrameAt = attempt.lastFrameAt ?: return // Initial frame timeout belongs to setup.
        val gap = clock() - lastFrameAt
        when {
            gap >= PLAYBACK_TIMEOUT_MILLIS -> fail(attempt,
                "已持续 20 秒未收到可播放画面，请重试；语音通话仍然可用",
                ScreenShareFailureCode.PlaybackStalled)
            gap >= FRAME_STALL_MILLIS -> buffering(attempt)
        }
    }

    @Synchronized
    private fun fail(attempt: Attempt, message: String, code: ScreenShareFailureCode) {
        if (active !== attempt) return
        if (code.reportable) runCatching { failures.report(attempt.request.callId, attempt.request.shareId, code) }
        mutableStatus.value = WhepPlaybackStatus.Failed(attempt.request, message)
        retireActive()
    }

    /** Detach immediately; cancelled setup and native close finish outside the UI path. */
    private fun retireActive() {
        val previous = active ?: return
        active = null
        diagnostics.stopReceiving(previous.epoch)
        previous.job.cancel()
        scope.launch {
            previous.job.join()
            runCatching { previous.session?.close() }
        }
    }

    private companion object {
        // A static mobile screen still sends at 5 fps. Permit brief GOP/network gaps.
        const val FRAME_STALL_MILLIS = 1_500L
        const val RECOVERY_MILLIS = 300L
        const val PLAYBACK_TIMEOUT_MILLIS = 20_000L
    }

    private class Attempt(val request: WhepPlaybackRequest, val epoch: Long) {
        lateinit var job: Job
        @Volatile var session: WhepSession? = null
        var lastFrameAt: Long? = null
        var recoveryStartedAt: Long? = null
    }
}
