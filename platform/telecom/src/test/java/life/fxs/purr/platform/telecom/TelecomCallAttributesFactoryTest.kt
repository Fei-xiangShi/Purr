package life.fxs.purr.platform.telecom

import androidx.core.telecom.CallAttributesCompat
import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.model.CallDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelecomCallAttributesFactoryTest {
    private val factory = TelecomCallAttributesFactory()

    @Test
    fun `incoming descriptor creates an incoming audio call`() {
        val attributes = factory.create(descriptor(CallDirection.Incoming))

        assertThat(attributes.displayName.toString()).isEqualTo("Partner")
        assertThat(attributes.address.toString()).isEqualTo("purr:pair%20%2F1")
        assertThat(attributes.direction).isEqualTo(CallAttributesCompat.DIRECTION_INCOMING)
        assertThat(attributes.callType).isEqualTo(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
        assertThat(attributes.callCapabilities).isEqualTo(0)
    }

    @Test
    fun `outgoing descriptor and blank name use explicit platform defaults`() {
        val attributes = factory.create(
            descriptor(CallDirection.Outgoing).copy(remoteDisplayName = "  "),
        )

        assertThat(attributes.displayName.toString()).isEqualTo("Purr")
        assertThat(attributes.direction).isEqualTo(CallAttributesCompat.DIRECTION_OUTGOING)
    }

    private fun descriptor(direction: CallDirection) = SystemCallDescriptor(
        callId = "call-1",
        pairId = "pair /1",
        remoteDisplayName = "Partner",
        direction = direction,
    )
}
