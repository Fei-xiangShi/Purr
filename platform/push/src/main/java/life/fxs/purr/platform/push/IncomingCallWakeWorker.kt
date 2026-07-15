package life.fxs.purr.platform.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import life.fxs.purr.core.common.AppResult

@HiltWorker
class IncomingCallWakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val recovery: IncomingCallWakeRecovery,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val callId = inputData.getString(KEY_CALL_ID)?.takeIf(::isValidCallId)
            ?: return Result.failure()
        return when (val recovered = recovery.recover()) {
            IncomingCallWakeRecoveryResult.NoAuthenticatedSession -> Result.success()
            is IncomingCallWakeRecoveryResult.Refreshed -> when (recovered.result) {
                is AppResult.Success -> Result.success()
                is AppResult.Failure -> Result.retry()
            }
        }
    }

    companion object {
        const val KEY_CALL_ID = "call_id"
    }
}
