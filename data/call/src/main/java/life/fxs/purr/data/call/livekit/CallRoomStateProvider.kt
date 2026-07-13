package life.fxs.purr.data.call.livekit

import io.livekit.android.room.Room
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface CallRoomStateProvider {
    val room: StateFlow<Room?>
}

@Singleton
class MutableCallRoomStateProvider @Inject constructor() : CallRoomStateProvider {
    private val mutableRoom = MutableStateFlow<Room?>(null)

    override val room: StateFlow<Room?> = mutableRoom.asStateFlow()

    fun update(room: Room?) {
        mutableRoom.value = room
    }
}
