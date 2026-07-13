package life.fxs.purr.data.call.livekit

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveKitRoomFactory @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    fun create(): Room = LiveKit.create(
        appContext = appContext,
        options = RoomOptions(),
    )
}
