package life.fxs.purr.core.media.screenshare

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenSharePublishRequest(
    val callId: String,
    val shareId: String,
    val whipUrl: String,
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
    val quality: ScreenShareQuality = ScreenShareQuality.FULL_HD60,
)

data class ScreenCapturePermission(
    val resultCode: Int,
    val data: Intent,
)

sealed interface ScreenSharePublisherStatus {
    data object Idle : ScreenSharePublisherStatus
    data class RequestingPermission(val request: ScreenSharePublishRequest) : ScreenSharePublisherStatus
    data class Connecting(val request: ScreenSharePublishRequest) : ScreenSharePublisherStatus
    data class Live(val request: ScreenSharePublishRequest) : ScreenSharePublisherStatus

    /** WHIP dropped; sources and MediaProjection are kept while the publisher retries. */
    data class Reconnecting(
        val request: ScreenSharePublishRequest,
        val attempt: Int,
    ) : ScreenSharePublisherStatus
    data class Stopping(val request: ScreenSharePublishRequest) : ScreenSharePublisherStatus
    data class Failed(
        val callId: String?,
        val shareId: String?,
        val message: String,
    ) : ScreenSharePublisherStatus
}

interface ScreenSharePublisherController {
    val status: StateFlow<ScreenSharePublisherStatus>
    fun prepare(request: ScreenSharePublishRequest)
    fun start(request: ScreenSharePublishRequest, permission: ScreenCapturePermission)
    fun permissionDenied(request: ScreenSharePublishRequest)
    fun stop(callId: String? = null)
}

/** Fresh owner publishing credentials fetched from the server while recovering. */
data class ScreenSharePublishCredentials(
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
)

/** The server definitively refused new credentials (share ended, not owner, auth gone). */
class ScreenSharePublishCredentialsRejectedException(message: String) : Exception(message)

interface ScreenSharePublishCredentialRefresher {
    /**
     * @throws ScreenSharePublishCredentialsRejectedException when the server refuses
     * @throws Exception for transient failures that may be retried
     */
    suspend fun refreshPublishing(callId: String, shareId: String): ScreenSharePublishCredentials
}

@Singleton
class ScreenSharePublisherStateStore @Inject constructor() {
    private val mutableStatus = MutableStateFlow<ScreenSharePublisherStatus>(
        ScreenSharePublisherStatus.Idle,
    )
    val status: StateFlow<ScreenSharePublisherStatus> = mutableStatus.asStateFlow()

    fun requestingPermission(request: ScreenSharePublishRequest) {
        mutableStatus.value = ScreenSharePublisherStatus.RequestingPermission(request)
    }

    fun connecting(request: ScreenSharePublishRequest) {
        mutableStatus.value = ScreenSharePublisherStatus.Connecting(request)
    }

    fun live(request: ScreenSharePublishRequest) {
        if (canTransitionToLive(request)) {
            mutableStatus.value = ScreenSharePublisherStatus.Live(request)
        }
    }

    fun reconnecting(request: ScreenSharePublishRequest, attempt: Int) {
        if (canTransitionToLive(request)) {
            mutableStatus.value = ScreenSharePublisherStatus.Reconnecting(request, attempt)
        }
    }

    private fun canTransitionToLive(request: ScreenSharePublishRequest): Boolean {
        val current = mutableStatus.value
        return (current is ScreenSharePublisherStatus.Connecting ||
            current is ScreenSharePublisherStatus.Live ||
            current is ScreenSharePublisherStatus.Reconnecting) &&
            current.requestOrNull() == request
    }

    fun stopping(request: ScreenSharePublishRequest) {
        if (mutableStatus.value.requestOrNull()?.shareId == request.shareId) {
            mutableStatus.value = ScreenSharePublisherStatus.Stopping(request)
        }
    }

    fun failed(callId: String?, shareId: String?, message: String) {
        val active = mutableStatus.value.requestOrNull()
        if (active != null && shareId != null && active.shareId != shareId) return
        mutableStatus.value = ScreenSharePublisherStatus.Failed(callId, shareId, message)
    }

    fun idle(callId: String? = null, shareId: String? = null) {
        if (shareId != null && mutableStatus.value.requestOrNull()?.shareId != shareId) return
        val activeCallId = mutableStatus.value.callIdOrNull()
        if (callId == null || activeCallId == null || activeCallId == callId) {
            mutableStatus.value = ScreenSharePublisherStatus.Idle
        }
    }
}

fun ScreenSharePublisherStatus.requestOrNull(): ScreenSharePublishRequest? = when (this) {
    is ScreenSharePublisherStatus.RequestingPermission -> request
    is ScreenSharePublisherStatus.Connecting -> request
    is ScreenSharePublisherStatus.Live -> request
    is ScreenSharePublisherStatus.Reconnecting -> request
    is ScreenSharePublisherStatus.Stopping -> request
    is ScreenSharePublisherStatus.Idle,
    is ScreenSharePublisherStatus.Failed,
    -> null
}

fun ScreenSharePublisherStatus.callIdOrNull(): String? = when (this) {
    is ScreenSharePublisherStatus.Failed -> callId
    else -> requestOrNull()?.callId
}
