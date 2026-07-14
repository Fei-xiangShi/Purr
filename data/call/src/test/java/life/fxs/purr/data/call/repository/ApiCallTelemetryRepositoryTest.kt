package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallTelemetryApi
import life.fxs.purr.domain.call.model.AudioQualityMetrics
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.model.DeviceCallMetrics
import life.fxs.purr.domain.call.model.NetworkTransport
import life.fxs.purr.domain.call.model.TransportQualityMetrics
import org.junit.Test

class ApiCallTelemetryRepositoryTest {
    private val api = mockk<PurrCallTelemetryApi>()

    @Test
    fun `maps quality metrics to the focused telemetry API`() = runTest {
        coEvery { api.report(any(), any()) } returns Unit
        val repository = ApiCallTelemetryRepository(api)

        val result = repository.report(
            callId = "call-1",
            sampledAtEpochMillis = 10_000L,
            metrics = CallQualityMetrics(
                audio = AudioQualityMetrics(sendCodec = "audio/opus", receiveCodec = "audio/opus"),
                transport = TransportQualityMetrics(
                    roundTripTimeMs = 42.0,
                    jitterMs = 8.0,
                    uplinkPacketLossPercent = 1.0,
                    downlinkPacketLossPercent = 2.0,
                    uplinkBitrateKbps = 64.0,
                    downlinkBitrateKbps = 96.0,
                ),
                device = DeviceCallMetrics(
                    networkTransport = NetworkTransport.Wifi,
                    networkValidated = true,
                ),
            ),
        )

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        coVerify(exactly = 1) {
            api.report(
                "call-1",
                match { sample ->
                    sample.sampledAtEpochMillis == 10_000L &&
                        sample.roundTripTimeMs == 42.0 &&
                        sample.networkTransport == "wifi" &&
                        sample.networkValidated
                },
            )
        }
    }
}
