package life.fxs.purr.platform.telecom

import android.os.ParcelUuid
import androidx.core.telecom.CallEndpointCompat
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import life.fxs.purr.core.model.AudioRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelecomEndpointMapperTest {
    @Test
    fun `known Telecom endpoint types map to vendor-neutral audio routes`() {
        assertThat(endpoint(CallEndpointCompat.TYPE_EARPIECE).toAudioRoute()).isEqualTo(AudioRoute.Earpiece)
        assertThat(endpoint(CallEndpointCompat.TYPE_BLUETOOTH).toAudioRoute()).isEqualTo(AudioRoute.Bluetooth)
        assertThat(endpoint(CallEndpointCompat.TYPE_WIRED_HEADSET).toAudioRoute()).isEqualTo(AudioRoute.WiredHeadset)
        assertThat(endpoint(CallEndpointCompat.TYPE_SPEAKER).toAudioRoute()).isEqualTo(AudioRoute.Speaker)
    }

    @Test
    fun `unknown Telecom endpoint does not leak into the domain route model`() {
        assertThat(endpoint(CallEndpointCompat.TYPE_UNKNOWN).toAudioRoute()).isNull()
    }

    private fun endpoint(type: Int) = CallEndpointCompat(
        "endpoint-$type",
        type,
        ParcelUuid(UUID.randomUUID()),
    )
}
