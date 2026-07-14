package life.fxs.purr.feature.incomingcall

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import life.fxs.purr.core.common.ApplicationScope

/**
 * The only owner of the background incoming-call reminder presentation.
 *
 * Inputs are reduced to one target before touching Android, so lifecycle and realtime updates
 * cannot independently create competing reminders.
 */
@Singleton
class IncomingCallReminderCoordinator @Inject internal constructor(
    private val source: IncomingCallSource,
    private val visibility: ApplicationVisibility,
    private val reminder: IncomingCallReminder,
    private val policy: IncomingCallReminderPolicy,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        combine(source.observe(), visibility.isForeground, policy::resolve)
            .distinctUntilChanged()
            .onEach { target ->
                when (target) {
                    IncomingCallReminderTarget.Hidden -> reminder.dismiss()
                    is IncomingCallReminderTarget.Visible -> reminder.replace(target.call)
                }
            }
            .launchIn(applicationScope)
    }
}
