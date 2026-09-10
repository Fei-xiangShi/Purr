package life.fxs.purr.data.call.repository

import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import life.fxs.purr.core.common.PurrLogger
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.SessionRequestDto
import life.fxs.purr.data.call.mapper.toCallTiming
import life.fxs.purr.data.call.mapper.toRecordingState
import life.fxs.purr.data.call.remote.CallStatusRemoteDataSource
import life.fxs.purr.data.call.runtime.CallMediaConnection
import life.fxs.purr.data.call.state.CallUiSnapshotAssembler
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.model.CallPreparationRequest

/** Creates a remote session, maps it, and owns compensation until the repository commits it. */
internal class CallSessionPreparationCoordinator @Inject constructor(
    private val api: PurrCallApi,
    private val callStatusRemoteDataSource: CallStatusRemoteDataSource,
    private val callUiSnapshotAssembler: CallUiSnapshotAssembler,
    private val logger: PurrLogger,
) {
    internal var compensationTimeoutMillis: Long = DEFAULT_COMPENSATION_TIMEOUT_MILLIS
    suspend fun prepare(request: CallPreparationRequest): PreparedCallSession {
        var ownedCallId: String? = null
        try {
            val response = api.createSession(
                SessionRequestDto(
                    pairId = request.pairId,
                    recordingConsent = request.recordingConsent,
                    expectedCallId = (request as? CallPreparationRequest.Existing)?.callId,
                ),
            )
            ownedCallId = response.callId.takeIf { response.createdByRequest }
            check(request !is CallPreparationRequest.NewOutgoing || response.createdByRequest) {
                "已有未结束的通话，请先处理当前来电"
            }
            if (request is CallPreparationRequest.Existing && response.callId != request.callId) {
                throw IllegalStateException("Prepared call identity does not match requested call")
            }
            val callStatus = callStatusRemoteDataSource.getStatus(response.callId)
            check(!callStatus.state.equals("ended", ignoreCase = true)) {
                "Call has already ended"
            }
            val session = callUiSnapshotAssembler.assemble(
                CallSession(
                    callId = response.callId,
                    pairId = response.pairId,
                    participantIdentity = ParticipantIdentity(local = response.participantIdentity),
                    roomName = response.roomName,
                    remoteDisplayName = request.remoteDisplayName,
                    direction = request.direction,
                    connectionState = CallConnectionState.Preparing,
                    localAudioState = LocalAudioState.Disabled,
                    recordingState = callStatus.recordingStatus.toRecordingState(),
                    timing = callStatus.toCallTiming(),
                    uiSnapshot = CallUiSnapshot(),
                ),
            )
            return PreparedCallSession(
                session = session,
                mediaConnection = CallMediaConnection(
                    wsUrl = response.wsUrl,
                    accessToken = response.token,
                ),
                ownedCallId = ownedCallId,
            )
        } catch (error: Throwable) {
            ownedCallId?.let { compensateOwnedCall(it) }
            throw error
        }
    }

    suspend fun compensate(prepared: PreparedCallSession) {
        prepared.ownedCallId?.let { compensateOwnedCall(it) }
    }

    private suspend fun compensateOwnedCall(callId: String) {
        withContext(NonCancellable) {
            val startedAt = System.nanoTime()
            try {
                withTimeout(compensationTimeoutMillis) { api.endCall(callId) }
                logger.d(
                    LOG_TAG,
                    "callId=$callId phase=prepare.compensation event=end elapsedMs=${elapsedMillis(startedAt)}",
                )
            } catch (timeout: TimeoutCancellationException) {
                logger.e(
                    LOG_TAG,
                    timeout,
                    "callId=$callId phase=prepare.compensation event=timeout " +
                        "elapsedMs=${elapsedMillis(startedAt)} reconciliation=server-status",
                )
            } catch (throwable: Throwable) {
                logger.e(
                    LOG_TAG,
                    throwable,
                    "callId=$callId phase=prepare.compensation event=error " +
                        "elapsedMs=${elapsedMillis(startedAt)} reconciliation=server-status",
                )
            }
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        const val LOG_TAG = "CallLifecycle"
        const val DEFAULT_COMPENSATION_TIMEOUT_MILLIS = 10_000L
    }
}

internal data class PreparedCallSession(
    val session: CallSession,
    val mediaConnection: CallMediaConnection,
    val ownedCallId: String?,
)
