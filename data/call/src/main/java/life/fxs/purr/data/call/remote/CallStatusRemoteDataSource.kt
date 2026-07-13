package life.fxs.purr.data.call.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallStatusDto

interface CallStatusRemoteDataSource {
    suspend fun getStatus(callId: String): CallStatusDto

    fun observeStatus(callId: String): Flow<CallStatusDto>
}

@Singleton
class ApiCallStatusRemoteDataSource @Inject constructor(
    private val api: PurrCallApi,
) : CallStatusRemoteDataSource {
    override suspend fun getStatus(callId: String): CallStatusDto = api.getCall(callId)

    override fun observeStatus(callId: String): Flow<CallStatusDto> = flow {
        while (currentCoroutineContext().isActive) {
            val status = try {
                getStatus(callId)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                null
            }
            if (status != null) {
                emit(status)
                if (status.state.equals("ended", ignoreCase = true)) return@flow
            }
            delay(STATUS_SYNC_INTERVAL_MILLIS)
        }
    }

    private companion object {
        const val STATUS_SYNC_INTERVAL_MILLIS = 2_000L
    }
}
