package life.fxs.purr.platform.push

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class IncomingCallWakeSchedulerTest {
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = WorkManagerIncomingCallWakeScheduler(workManager)

    @Test
    fun `duplicate call signals use the same keep-only unique work`() {
        val signal = IncomingCallWakeSignal("call-123", 1_752_580_800_000L)

        scheduler.schedule(signal)
        scheduler.schedule(signal)

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                "purr-incoming-call:call-123",
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
