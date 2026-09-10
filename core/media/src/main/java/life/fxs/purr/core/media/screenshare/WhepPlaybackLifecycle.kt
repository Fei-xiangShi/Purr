package life.fxs.purr.core.media.screenshare

import java.io.Closeable
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
    fun onBuffering()
    /** Returns whether the frame still belongs to the visible playback attempt. */
    fun onFrame(width: Int, height: Int): Boolean
    fun onFailed(message: String)
}

/** Owns attempts by identity, including reconnects using the same credentials. */
internal class WhepPlaybackLifecycle(
    private val scope: CoroutineScope,
    private val factory: (WhepPlaybackRequest, WhepSessionListener) -> WhepSession,
) {
    private val mutableStatus = MutableStateFlow<WhepPlaybackStatus>(WhepPlaybackStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private var active: Attempt? = null

    @Synchronized
    fun start(request: WhepPlaybackRequest) {
        if (active?.request == request) return
        retireActive()
        val attempt = Attempt(request)
        active = attempt
        mutableStatus.value = WhepPlaybackStatus.Connecting(request)
        attempt.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val session = factory(request, object : WhepSessionListener {
                    override fun onBuffering() {
                        update(attempt, WhepPlaybackStatus.Buffering(request))
                    }

                    override fun onFrame(width: Int, height: Int): Boolean =
                        update(attempt, WhepPlaybackStatus.Live(request, width, height))

                    override fun onFailed(message: String) = fail(attempt, message)
                })
                attempt.session = session
                withTimeout(40_000L) { session.connect() }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) fail(attempt, "共享画面连接超时")
                else throw error
            } catch (error: Throwable) {
                fail(attempt, error.message?.takeIf(String::isNotBlank) ?: "共享画面连接失败")
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
    private fun update(attempt: Attempt, status: WhepPlaybackStatus): Boolean {
        if (active !== attempt) return false
        mutableStatus.value = status
        return true
    }

    @Synchronized
    private fun fail(attempt: Attempt, message: String) {
        if (active !== attempt) return
        mutableStatus.value = WhepPlaybackStatus.Failed(attempt.request, message)
        retireActive()
    }

    /** Detach immediately; cancelled setup and native close finish outside the UI path. */
    private fun retireActive() {
        val previous = active ?: return
        active = null
        previous.job.cancel()
        scope.launch {
            previous.job.join()
            runCatching { previous.session?.close() }
        }
    }

    private class Attempt(val request: WhepPlaybackRequest) {
        lateinit var job: Job
        @Volatile var session: WhepSession? = null
    }
}
