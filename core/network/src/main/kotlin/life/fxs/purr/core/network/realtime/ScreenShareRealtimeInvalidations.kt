package life.fxs.purr.core.network.realtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class ScreenShareRealtimeInvalidation(
    val callId: String,
    val shareId: String?,
    val status: String?,
    val source: String?,
    val ownerUserId: String?,
)

@Singleton
class ScreenShareRealtimeInvalidations @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<ScreenShareRealtimeInvalidation>(
        extraBufferCapacity = 16,
    )

    val events: Flow<ScreenShareRealtimeInvalidation> = mutableEvents.asSharedFlow()

    fun publish(event: ScreenShareRealtimeInvalidation) {
        mutableEvents.tryEmit(event)
    }
}
