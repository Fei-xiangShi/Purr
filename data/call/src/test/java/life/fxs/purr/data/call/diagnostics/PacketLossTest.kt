package life.fxs.purr.data.call.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PacketLossTest {
    @Test
    fun `uplink packets sent already includes lost packets`() {
        assertThat(sentPacketLossPercent(lost = 10.0, sent = 100.0)).isEqualTo(10.0)
        assertThat(packetLossPercent(lost = 10.0, delivered = 90.0)).isEqualTo(10.0)
    }

    @Test
    fun `reordered and inconsistent counters stay bounded and missing stats stay unknown`() {
        assertThat(sentPacketLossPercent(-1.0, 100.0)).isEqualTo(0.0)
        assertThat(sentPacketLossPercent(120.0, 100.0)).isEqualTo(100.0)
        assertThat(packetLossPercent(-2.0, 0.0)).isNull()
        assertThat(sentPacketLossPercent(null, 100.0)).isNull()
        assertThat(sentPacketLossPercent(Double.NaN, 100.0)).isNull()
    }
}
