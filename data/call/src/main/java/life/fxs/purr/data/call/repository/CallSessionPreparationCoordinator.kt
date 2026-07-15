package life.fxs.purr.data.call.repository

import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import life.fxs.purr.domain.call.model.PrepareCallParams

/** Creates a remote session, maps it, and owns compensation until the repository commits it. */
internal class CallSessionPreparationCoordinator @Inject constructor(
    private val api: PurrCallApi,
    private val callStatusRemoteDataSource: CallStatusRemoteDataSource,
    private val callUiSnapshotAssembler: CallUiSnapshotAssembler,
) {
    suspend fun prepare(params: PrepareCallParams): PreparedCallSession {
        var ownedCallId: String? = null
        try {
            val response = api.createSession(
                SessionRequestDto(
                    pairId = params.pairId,
                    recordingConsent = params.recordingConsent,
                    expectedCallId = params.expectedCallId,
                ),
            )
            ownedCallId = response.callId.takeIf { response.createdByRequest }
            check(params.expectedCallId == null || response.callId == params.expectedCallId) {
                "Prepared call identity does not match the requested incoming call"
            }
            val callStatus = callStatusRemoteDataSource.getStatus(response.callId)
            val session = callUiSnapshotAssembler.assemble(
                CallSession(
                    callId = response.callId,
                    pairId = response.pairId,
                    participantIdentity = ParticipantIdentity(local = response.participantIdentity),
                    roomName = response.roomName,
                    remoteDisplayName = params.remoteDisplayName,
                    direction = params.direction,
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
            runCatching { api.endCall(callId) }
        }
    }
}

internal data class PreparedCallSession(
    val session: CallSession,
    val mediaConnection: CallMediaConnection,
    val ownedCallId: String?,
)
