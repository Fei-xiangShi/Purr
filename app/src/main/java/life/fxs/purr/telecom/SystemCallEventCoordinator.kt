package life.fxs.purr.telecom

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.incomingcall.IncomingCallReminderContent
import life.fxs.purr.incomingcall.IncomingCallUiLauncher

@Singleton
class SystemCallEventCoordinator @Inject constructor(
    private val systemCallController: SystemCallController,
    private val disconnectCall: DisconnectCallUseCase,
    private val declineIncomingCall: DeclineIncomingCallUseCase,
    private val observeCallState: ObserveCallStateUseCase,
    private val observeRealtimeState: ObserveRealtimeStateUseCase,
    private val observePairBond: ObservePairBondUseCase,
    private val incomingCallUiLauncher: IncomingCallUiLauncher,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        systemCallController.events
            .onEach { event ->
                when (event) {
                    is SystemCallEvent.AnswerRequested -> handleAnswerRequest(event.callId)
                    is SystemCallEvent.DisconnectRequested -> handleDisconnectRequest(event.callId)
                }
            }
            .launchIn(applicationScope)
    }

    private suspend fun handleDisconnectRequest(callId: String) {
        try {
            val localSession = observeCallState().first()
            val result = if (
                localSession?.callId == callId && localSession.connectionState.isOngoing
            ) {
                disconnectCall(callId)
            } else {
                val candidate = observeRealtimeState().first().incomingCallCandidate
                if (candidate?.callId != callId) return
                declineIncomingCall(callId)
            }
            logFailure("disconnect", callId, result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Telecom disconnect handling failed for $callId", error)
        }
    }

    private suspend fun handleAnswerRequest(callId: String) {
        val target = try {
            resolveAnswerTarget(callId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to resolve Telecom answer target for $callId", error)
            runCatching { systemCallController.disconnectCall(callId) }
                .onFailure { cleanup -> Log.e(TAG, "Unable to release unresolved Telecom call $callId", cleanup) }
            return
        }

        if (target == null) {
            runCatching { systemCallController.disconnectCall(callId) }
                .onFailure { error -> Log.e(TAG, "Unable to release stale Telecom call $callId", error) }
            return
        }

        try {
            incomingCallUiLauncher.launchAnswer(target.content)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to foreground answered Telecom call $callId", error)
            try {
                val result = if (target.hasPreparedSession) {
                    disconnectCall(callId)
                } else {
                    declineIncomingCall(callId)
                }
                logFailure("answer rollback", callId, result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (rollbackError: Throwable) {
                Log.e(TAG, "Unable to roll back answered Telecom call $callId", rollbackError)
            }
            runCatching { systemCallController.disconnectCall(callId) }
                .onFailure { cleanup -> error.addSuppressed(cleanup) }
        }
    }

    private suspend fun resolveAnswerTarget(callId: String): AnswerTarget? {
        val localSession = observeCallState().first()
        val candidate = observeRealtimeState().first().incomingCallCandidate
        val pairBond = observePairBond().first()
        if (
            localSession?.callId == callId &&
            localSession.direction == CallDirection.Incoming &&
            localSession.connectionState.isOngoing
        ) {
            val caller = pairBond?.takeIf { it.pairId == localSession.pairId }?.partner
            return AnswerTarget(
                content = IncomingCallReminderContent(
                    callId = localSession.callId,
                    pairId = localSession.pairId,
                    callerName = localSession.remoteDisplayName,
                    callerAvatarUrl = caller?.avatarUrl,
                    startedAtEpochMillis = localSession.timing.startedAtEpochMillis
                        ?: candidate?.takeIf { it.callId == callId }?.startedAtEpochMillis
                        ?: 0L,
                ),
                hasPreparedSession = true,
            )
        }

        candidate?.takeIf { it.callId == callId } ?: return null
        val caller = pairBond
            ?.takeIf { it.pairId == candidate.pairId }
            ?.partner
            ?.takeIf { it.userId == candidate.callerUserId }
        return AnswerTarget(
            content = candidate.toReminderContent(
                callerName = caller?.displayName,
                callerAvatarUrl = caller?.avatarUrl,
            ),
            hasPreparedSession = false,
        )
    }

    private fun logFailure(operation: String, callId: String, result: AppResult<Unit>) {
        if (result is AppResult.Failure) {
            Log.e(TAG, "Unable to complete Telecom $operation for $callId: ${result.error}")
        }
    }

    private companion object {
        const val TAG = "SystemCallEvents"
    }
}

private data class AnswerTarget(
    val content: IncomingCallReminderContent,
    val hasPreparedSession: Boolean,
)

private fun IncomingCall.toReminderContent(
    callerName: String?,
    callerAvatarUrl: String?,
) = IncomingCallReminderContent(
    callId = callId,
    pairId = pairId,
    callerName = callerName,
    callerAvatarUrl = callerAvatarUrl,
    startedAtEpochMillis = startedAtEpochMillis,
)
