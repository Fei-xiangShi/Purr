package life.fxs.purr.feature.call

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.domain.call.model.CallQualityMetrics
import life.fxs.purr.domain.call.model.DeviceCallMetrics
import life.fxs.purr.domain.call.model.TransportQualityMetrics
import life.fxs.purr.domain.call.repository.CallDiagnosticsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDiagnosticsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `metrics are exposed through the repository abstraction`() = runTest(dispatcher) {
        val upstream = MutableSharedFlow<CallQualityMetrics>()
        val repository = mockk<CallDiagnosticsRepository>()
        every { repository.observeMetrics() } returns upstream
        val viewModel = CallDiagnosticsViewModel(repository)
        val expected = CallQualityMetrics(
            transport = TransportQualityMetrics(
                sampledAtMillis = 1_000L,
                roundTripTimeMs = 42.0,
            ),
        )

        viewModel.networkHistory.test {
            assertThat(awaitItem()).isEmpty()
            viewModel.metrics.test {
                assertThat(awaitItem()).isEqualTo(CallQualityMetrics())
                upstream.emit(expected)
                assertThat(awaitItem()).isEqualTo(expected)
            }
            assertThat(awaitItem()).containsExactly(
                NetworkGraphSample(
                    sampledAtMillis = 1_000L,
                    roundTripTimeMs = 42.0,
                    jitterMs = null,
                    packetLossPercent = null,
                    uplinkBitrateKbps = null,
                    downlinkBitrateKbps = null,
                    estimatedUpstreamKbps = null,
                    estimatedDownstreamKbps = null,
                ),
            )
        }
    }

    @Test
    fun `local metrics are exposed before transport samples exist`() = runTest(dispatcher) {
        val upstream = MutableSharedFlow<CallQualityMetrics>()
        val repository = mockk<CallDiagnosticsRepository>()
        every { repository.observeMetrics() } returns upstream
        val viewModel = CallDiagnosticsViewModel(repository)
        val localOnly = CallQualityMetrics(
            device = DeviceCallMetrics(
                networkValidated = true,
                callVolumePercent = 64,
                estimatedUpstreamKbps = 12_000.0,
            ),
        )

        viewModel.networkHistory.test {
            assertThat(awaitItem()).isEmpty()
            viewModel.metrics.test {
                assertThat(awaitItem()).isEqualTo(CallQualityMetrics())
                upstream.emit(localOnly)
                assertThat(awaitItem()).isEqualTo(localOnly)
            }
            expectNoEvents()
        }
    }

    @Test
    fun `network history ignores repeated timestamps and keeps latest window`() = runTest(dispatcher) {
        val upstream = MutableSharedFlow<CallQualityMetrics>()
        val repository = mockk<CallDiagnosticsRepository>()
        every { repository.observeMetrics() } returns upstream
        val viewModel = CallDiagnosticsViewModel(repository)

        viewModel.networkHistory.test {
            assertThat(awaitItem()).isEmpty()

            upstream.emit(metricsAt(sampledAtMillis = 1L, roundTripTimeMs = 10.0))
            assertThat(awaitItem()).hasSize(1)
            upstream.emit(metricsAt(sampledAtMillis = 1L, roundTripTimeMs = 20.0))
            expectNoEvents()

            var latest = emptyList<NetworkGraphSample>()
            for (sampledAtMillis in 2L..151L) {
                upstream.emit(metricsAt(sampledAtMillis, roundTripTimeMs = sampledAtMillis.toDouble()))
                latest = awaitItem()
            }

            assertThat(latest).hasSize(150)
            assertThat(latest.first().sampledAtMillis).isEqualTo(2L)
            assertThat(latest.last().sampledAtMillis).isEqualTo(151L)
        }
    }

    @Test
    fun `network history prunes samples outside timestamp window`() {
        val history = listOf(graphSampleAt(1_000L), graphSampleAt(2_000L))

        val updated = appendNetworkGraphSample(history, graphSampleAt(8_501L))

        assertThat(updated.map { it.sampledAtMillis }).containsExactly(2_000L, 8_501L).inOrder()
    }

    @Test
    fun `network history resets when monotonic timestamps regress`() {
        val replacement = graphSampleAt(100L)

        val updated = appendNetworkGraphSample(listOf(graphSampleAt(1_000L)), replacement)

        assertThat(updated).containsExactly(replacement)
    }

    private fun metricsAt(sampledAtMillis: Long, roundTripTimeMs: Double) = CallQualityMetrics(
        transport = TransportQualityMetrics(
            sampledAtMillis = sampledAtMillis,
            roundTripTimeMs = roundTripTimeMs,
        ),
    )

    private fun graphSampleAt(sampledAtMillis: Long) = NetworkGraphSample(
        sampledAtMillis = sampledAtMillis,
        roundTripTimeMs = null,
        jitterMs = null,
        packetLossPercent = null,
        uplinkBitrateKbps = null,
        downlinkBitrateKbps = null,
        estimatedUpstreamKbps = null,
        estimatedDownstreamKbps = null,
    )
}
