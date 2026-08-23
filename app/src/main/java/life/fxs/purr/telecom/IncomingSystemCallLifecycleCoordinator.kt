package life.fxs.purr.telecom

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase

/** Projects incoming business-call state into exactly one Android Telecom call. */
@Singleton
class IncomingSystemCallLifecycleCoordinator @Inject internal constructor(
    observeRealtimeState: ObserveRealtimeStateUseCase,
    observeCallState: ObserveCallStateUseCase,
    observePairBond: ObservePairBondUseCase,
    private val systemCallController: SystemCallController,
    private val targetResolver: IncomingSystemCallTargetResolver,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val targets = combine(
        observeRealtimeState().map { it.incomingCallCandidate },
        observeCallState(),
        observePairBond(),
        targetResolver::resolve,
    ).distinctUntilChanged()
    private val started = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()
    private var managedCallId: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            targets.collectLatest(::reconcile)
        }
    }

    private suspend fun reconcile(target: IncomingSystemCallTarget?) = lifecycleMutex.withLock {
        val currentCallId = managedCallId
        if (currentCallId == target?.descriptor?.callId) return@withLock

        if (currentCallId != null) {
            try {
                systemCallController.disconnectCall(currentCallId)
                managedCallId = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to release incoming Telecom call $currentCallId", error)
                // The old owner is no longer authoritative for business state.
                // Do not let a failed best-effort Telecom cleanup gate the next
                // call; the platform controller owns its own eventual teardown.
                managedCallId = null
            }
        }

        if (target != null) {
            try {
                systemCallController.startCall(target.descriptor)
                managedCallId = target.descriptor.callId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to register incoming Telecom call ${target.descriptor.callId}", error)
            }
        }
    }

    private companion object {
        const val TAG = "IncomingSystemCall"
    }
}

internal data class IncomingSystemCallTarget(
    val descriptor: SystemCallDescriptor,
)

internal class IncomingSystemCallTargetResolver @Inject constructor() {
    fun resolve(
        candidate: IncomingCall?,
        localSession: CallSession?,
        pairBond: PairBond?,
    ): IncomingSystemCallTarget? {
        if (localSession?.connectionState?.isOngoing == true) {
            if (localSession.direction != CallDirection.Incoming) return null
            return IncomingSystemCallTarget(
                SystemCallDescriptor(
                    callId = localSession.callId,
                    pairId = localSession.pairId,
                    remoteDisplayName = localSession.remoteDisplayName,
                    direction = CallDirection.Incoming,
                ),
            )
        }

        candidate ?: return null
        val caller = pairBond
            ?.takeIf { it.pairId == candidate.pairId }
            ?.partner
            ?.takeIf { it.userId == candidate.callerUserId }
        return IncomingSystemCallTarget(
            SystemCallDescriptor(
                callId = candidate.callId,
                pairId = candidate.pairId,
                remoteDisplayName = caller?.displayName ?: DEFAULT_DISPLAY_NAME,
                direction = CallDirection.Incoming,
            ),
        )
    }

    private companion object {
        const val DEFAULT_DISPLAY_NAME = "Purr"
    }
}
