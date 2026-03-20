package life.fxs.purr.feature.call

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.livekit.android.room.Room
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrStatusChip

@Composable
internal fun LiveKitRoomStatus(
    room: Room,
) {
    PurrPanel(title = "连接信息") {
        val remoteParticipant = room.remoteParticipants.keys.firstOrNull()?.toString()
        PurrStatusChip(
            label = "房间",
            detail = room.state.name,
            accentColor = MaterialTheme.colorScheme.primary,
        )
        PurrStatusChip(
            label = if (remoteParticipant != null) "远端已加入" else "等待远端加入",
            detail = remoteParticipant ?: "",
            accentColor = if (remoteParticipant != null) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}
