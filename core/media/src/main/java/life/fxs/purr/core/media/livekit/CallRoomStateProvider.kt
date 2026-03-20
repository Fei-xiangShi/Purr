package life.fxs.purr.core.media.livekit

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
    private val _room = MutableStateFlow<Room?>(null)

    override val room: StateFlow<Room?> = _room.asStateFlow()

    fun update(room: Room?) {
        _room.value = room
    }
}
