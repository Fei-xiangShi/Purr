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
}
