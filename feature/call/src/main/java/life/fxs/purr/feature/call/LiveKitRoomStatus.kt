package life.fxs.purr.feature.call

import androidx.compose.runtime.Composable
import io.livekit.android.room.Room
import life.fxs.purr.core.designsystem.component.PurrStatusChip

@Composable
internal fun LiveKitRoomStatus(
    room: Room,
) {
    PurrStatusChip(label = "LiveKit: ${room.state}")
}
