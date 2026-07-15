package life.fxs.purr.platform.push

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

internal interface IncomingCallWakeScheduler {
    fun schedule(signal: IncomingCallWakeSignal)
}

@Singleton
internal class WorkManagerIncomingCallWakeScheduler @Inject constructor(
    private val workManager: WorkManager,
) : IncomingCallWakeScheduler {
    override fun schedule(signal: IncomingCallWakeSignal) {
        val request = OneTimeWorkRequestBuilder<IncomingCallWakeWorker>()
            .setInputData(
                Data.Builder()
                    .putString(IncomingCallWakeWorker.KEY_CALL_ID, signal.callId)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag(TAG_INCOMING_CALL_WAKE)
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(signal.callId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal fun uniqueWorkName(callId: String): String = "$UNIQUE_WORK_PREFIX$callId"

    private companion object {
        const val UNIQUE_WORK_PREFIX = "purr-incoming-call:"
        const val TAG_INCOMING_CALL_WAKE = "purr-incoming-call-wake"
    }
}
