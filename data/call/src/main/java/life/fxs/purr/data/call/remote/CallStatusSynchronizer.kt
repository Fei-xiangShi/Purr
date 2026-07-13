package life.fxs.purr.data.call.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.network.model.CallStatusDto

/** Owns the single process-local observation of server-authoritative call status. */
@Singleton
class CallStatusSynchronizer @Inject constructor(
    private val remoteDataSource: CallStatusRemoteDataSource,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val observationLock = Any()
    private var activeObservation: Observation? = null

    fun start(
        callId: String,
        onStatus: suspend (CallStatusDto) -> Boolean,
    ) {
        val observation = Observation()
        observation.job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                remoteDataSource.observeStatus(callId)
                    .takeWhile(onStatus)
                    .collect()
            } finally {
                synchronized(observationLock) {
                    if (activeObservation === observation) {
                        activeObservation = null
                    }
                }
            }
        }
        val previous = synchronized(observationLock) {
            activeObservation.also { activeObservation = observation }
        }
        previous?.job?.cancel()
        observation.job.start()
    }

    fun stop() {
        val observation = synchronized(observationLock) {
            activeObservation.also { activeObservation = null }
        }
        observation?.job?.cancel()
    }

    private class Observation {
        lateinit var job: Job
    }
}
