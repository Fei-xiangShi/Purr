package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherController
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherStatus
import life.fxs.purr.core.media.screenshare.callIdOrNull
import life.fxs.purr.core.media.screenshare.requestOrNull
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CreateScreenShareRequestDto
import life.fxs.purr.core.network.model.ScreenShareDto
import life.fxs.purr.core.network.model.ScreenShareMediaEndpointDto
import life.fxs.purr.core.network.model.ScreenSharePublishingDto
import life.fxs.purr.core.network.model.ScreenShareSrtDto
import life.fxs.purr.core.network.realtime.ScreenShareRealtimeInvalidations
import life.fxs.purr.domain.account.repository.AuthRepository
import life.fxs.purr.domain.call.model.LocalScreenShareState
import life.fxs.purr.domain.call.model.ScreenShareMediaEndpoint
import life.fxs.purr.domain.call.model.ScreenSharePublishing
import life.fxs.purr.domain.call.model.ScreenShareSession
import life.fxs.purr.domain.call.model.ScreenShareSnapshot
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.model.ScreenShareSrtSettings
import life.fxs.purr.domain.call.model.ScreenShareStatus
import life.fxs.purr.domain.call.repository.ScreenShareRepository

@Singleton
class ScreenShareRepositoryImpl @Inject constructor(
    private val api: PurrCallApi,
    private val authRepository: AuthRepository,
    private val invalidations: ScreenShareRealtimeInvalidations,
    private val publisherController: ScreenSharePublisherController,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ScreenShareRepository {
    private val serverStates = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    private val refreshMutex = Mutex()
    private val autoStoppedShares = mutableSetOf<String>()

    init {
        applicationScope.launch {
            publisherController.status.collect { status ->
                val failure = status as? ScreenSharePublisherStatus.Failed ?: return@collect
                val callId = failure.callId ?: return@collect
                val shareId = failure.shareId ?: return@collect
                val shouldStop = synchronized(autoStoppedShares) { autoStoppedShares.add(shareId) }
                if (shouldStop) stopRemoteOnly(callId, shareId)
            }
        }
    }

    override fun observe(callId: String): Flow<ScreenShareSnapshot> = channelFlow {
        val stateJob = launch {
            combine(
                serverStates.map { states -> states[callId] ?: ServerState() },
                publisherController.status,
            ) { serverState, publisherStatus ->
                val currentUserId = authRepository.currentSession()?.self?.userId
                val owned = serverState.session?.ownerUserId == currentUserId
                ScreenShareSnapshot(
                    callId = callId,
                    session = serverState.session,
                    isOwnedByCurrentUser = owned,
                    localState = publisherStatus.toDomainLocalState(callId, serverState.session, owned),
                    syncErrorMessage = serverState.syncErrorMessage,
                )
            }.collect(::send)
        }
        val invalidationJob = launch {
            invalidations.events
                .filter { event -> event.callId == callId }
                .collect { refresh(callId) }
        }
        val recoveryJob = launch {
            refresh(callId)
            while (true) {
                delay(RECOVERY_INTERVAL_MILLIS)
                refresh(callId)
            }
        }
        awaitClose {
            stateJob.cancel()
            invalidationJob.cancel()
            recoveryJob.cancel()
        }
    }

    override suspend fun create(
        callId: String,
        source: ScreenShareSource,
    ): AppResult<ScreenShareSession> = refreshMutex.withLock {
        when (val result = callApiResult {
            requireNotNull(
                api.createScreenShare(
                    callId,
                    CreateScreenShareRequestDto(source.wireValue),
                ).screenShare,
            ) { "Screen-share creation returned no session" }.toDomain()
        }) {
            is AppResult.Success -> {
                updateServerState(callId, result.value, null)
                synchronized(autoStoppedShares) { autoStoppedShares.remove(result.value.shareId) }
                result
            }
            is AppResult.Failure -> {
                updateSyncFailure(callId, result.error.toScreenShareMessage())
                result
            }
        }
    }

    override suspend fun refresh(callId: String): AppResult<ScreenShareSession?> =
        refreshMutex.withLock {
            when (val result = callApiResult { api.getScreenShare(callId).screenShare?.toDomain() }) {
                is AppResult.Success -> {
                    val previous = serverStates.value[callId]?.session
                    val merged = result.value?.preservingPublishingFrom(previous)
                    updateServerState(callId, merged, null)
                    AppResult.Success(merged)
                }
                is AppResult.Failure -> {
                    updateSyncFailure(callId, result.error.toScreenShareMessage())
                    result
                }
            }
        }

    override suspend fun stop(callId: String): AppResult<ScreenShareSession?> {
        publisherController.stop(callId)
        return stopRemoteOnly(callId, serverStates.value[callId]?.session?.shareId)
    }

    private suspend fun stopRemoteOnly(callId: String, expectedShareId: String?): AppResult<ScreenShareSession?> =
        refreshMutex.withLock {
            when (val result = callApiResult { api.stopScreenShare(callId, expectedShareId).screenShare?.toDomain() }) {
                is AppResult.Success -> {
                    updateServerState(callId, result.value, null)
                    result
                }
                is AppResult.Failure -> {
                    updateSyncFailure(callId, result.error.toScreenShareMessage())
                    result
                }
            }
        }

    private fun updateServerState(
        callId: String,
        session: ScreenShareSession?,
        errorMessage: String?,
    ) {
        serverStates.update { it + (callId to ServerState(session, errorMessage)) }
        if (session != null && session.status !in setOf(ScreenShareStatus.Authorized, ScreenShareStatus.Live) &&
            publisherController.status.value.requestOrNull()?.shareId == session.shareId
        ) {
            publisherController.stop(callId)
        }
    }

    private fun updateSyncFailure(callId: String, errorMessage: String) {
        val current = serverStates.value[callId] ?: ServerState()
        updateServerState(callId, current.session, errorMessage)
    }

    private data class ServerState(
        val session: ScreenShareSession? = null,
        val syncErrorMessage: String? = null,
    )

    private companion object {
        const val RECOVERY_INTERVAL_MILLIS = 2_000L
    }
}

private fun ScreenSharePublisherStatus.toDomainLocalState(
    callId: String,
    session: ScreenShareSession?,
    owned: Boolean,
): LocalScreenShareState {
    val matchesShare = requestOrNull()?.shareId?.let { it == session?.shareId }
        ?: ((this as? ScreenSharePublisherStatus.Failed)?.shareId == session?.shareId)
    if (callIdOrNull() == callId && matchesShare &&
        session?.status in setOf(ScreenShareStatus.Authorized, ScreenShareStatus.Live)
    ) {
        return when (this) {
            is ScreenSharePublisherStatus.RequestingPermission ->
                LocalScreenShareState.RequestingPermission(request.shareId)
            is ScreenSharePublisherStatus.Connecting ->
                LocalScreenShareState.Connecting(request.shareId)
            is ScreenSharePublisherStatus.Live -> LocalScreenShareState.Live(request.shareId)
            is ScreenSharePublisherStatus.Stopping -> LocalScreenShareState.Stopping(request.shareId)
            is ScreenSharePublisherStatus.Failed ->
                LocalScreenShareState.Failed(shareId, message)
            ScreenSharePublisherStatus.Idle -> LocalScreenShareState.Idle
        }
    }
    if (!owned || session == null) return LocalScreenShareState.Idle
    return when (session.status) {
        ScreenShareStatus.Authorized -> LocalScreenShareState.Connecting(session.shareId)
        ScreenShareStatus.Live -> LocalScreenShareState.Live(session.shareId)
        ScreenShareStatus.Stopping -> LocalScreenShareState.Stopping(session.shareId)
        ScreenShareStatus.Failed,
        ScreenShareStatus.Expired,
        -> LocalScreenShareState.Failed(
            session.shareId,
            session.errorMessage ?: "投屏已失败或凭证已过期",
        )
        ScreenShareStatus.Stopped -> LocalScreenShareState.Idle
    }
}

private fun ScreenShareSession.preservingPublishingFrom(previous: ScreenShareSession?): ScreenShareSession =
    if (previous?.shareId == shareId && publishing == null &&
        status in setOf(ScreenShareStatus.Authorized, ScreenShareStatus.Live)
    ) copy(publishing = previous.publishing) else this

private fun ScreenShareDto.toDomain() = ScreenShareSession(
    shareId = shareId,
    callId = callId,
    ownerUserId = ownerUserId,
    source = ScreenShareSource.entries.firstOrNull { it.wireValue == source }
        ?: error("Unsupported screen-share source: $source"),
    status = ScreenShareStatus.entries.firstOrNull { it.wireValue == status }
        ?: error("Unsupported screen-share status: $status"),
    mediaPath = mediaPath,
    createdAtEpochMillis = createdAtEpochMillis,
    expiresAtEpochMillis = expiresAtEpochMillis,
    liveAtEpochMillis = liveAtEpochMillis,
    stoppedAtEpochMillis = stoppedAtEpochMillis,
    publishing = publishing?.toDomain(),
    playback = playback?.toDomain(),
    errorMessage = errorMessage,
)

private fun ScreenSharePublishingDto.toDomain() = ScreenSharePublishing(
    whip = whip.toDomain(),
    srt = srt?.toDomain(),
)

private fun ScreenShareMediaEndpointDto.toDomain() = ScreenShareMediaEndpoint(
    url = url,
    bearerToken = bearerToken,
    expiresAtEpochMillis = expiresAtEpochMillis,
)

private fun ScreenShareSrtDto.toDomain() = ScreenShareSrtSettings(
    url = url,
    streamId = streamId,
    passphrase = passphrase,
)

private fun AppError.toScreenShareMessage(): String = when (this) {
    is AppError.Network -> "投屏服务网络连接失败"
    is AppError.Unauthorized -> "投屏授权已失效，请重新登录"
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "投屏服务暂时不可用"
}
