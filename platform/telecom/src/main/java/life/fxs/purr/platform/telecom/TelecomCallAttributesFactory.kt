package life.fxs.purr.platform.telecom

import android.net.Uri
import androidx.core.telecom.CallAttributesCompat
import javax.inject.Inject
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.model.CallDirection

internal class TelecomCallAttributesFactory @Inject constructor() {
    fun create(descriptor: SystemCallDescriptor) = CallAttributesCompat(
        displayName = descriptor.remoteDisplayName.takeIf(String::isNotBlank) ?: DEFAULT_DISPLAY_NAME,
        address = Uri.parse("purr:${Uri.encode(descriptor.pairId)}"),
        direction = when (descriptor.direction) {
            CallDirection.Incoming -> CallAttributesCompat.DIRECTION_INCOMING
            CallDirection.Outgoing -> CallAttributesCompat.DIRECTION_OUTGOING
        },
        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        callCapabilities = 0,
    )

    private companion object {
        const val DEFAULT_DISPLAY_NAME = "Purr"
    }
}
