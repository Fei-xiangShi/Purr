package life.fxs.purr.domain.incomingcall

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase

/** Owns the one background incoming-call reminder slot. */
@Singleton
class IncomingCallReminderCoordinator @Inject internal constructor(
    observePresentableIncomingCall: ObservePresentableIncomingCallUseCase,
    observePairBond: ObservePairBondUseCase,
    private val visibility: ApplicationVisibility,
    private val reminder: IncomingCallReminder,
    private val policy: IncomingCallReminderPolicy,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val calls = observePresentableIncomingCall()
    private val callers = observePairBond().map { it?.partner }
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        combine(calls, callers, visibility.isForeground, policy::resolve)
            .distinctUntilChanged()
            .onEach { target ->
                when (target) {
                    IncomingCallReminderTarget.Hidden -> reminder.dismiss()
                    is IncomingCallReminderTarget.Visible -> reminder.replace(target.content)
                }
            }
            .launchIn(applicationScope)
    }
}
