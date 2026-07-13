package life.fxs.purr.data.call.remote

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.network.model.CallStatusDto
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallStatusSynchronizerTest {
    @Test
    fun `terminal status stops observation without cancelling its handler`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val remoteDataSource = mockk<CallStatusRemoteDataSource>()
        every { remoteDataSource.observeStatus("call-1") } returns flowOf(
            status("waiting"),
            status("ended"),
            status("active"),
        )
        val synchronizer = CallStatusSynchronizer(remoteDataSource, scope)
        val handled = mutableListOf<String>()

        synchronizer.start("call-1") { status ->
            handled += status.state
            status.state != "ended"
        }
        advanceUntilIdle()

        assertThat(handled).containsExactly("waiting", "ended").inOrder()
        scope.cancel()
    }

    private fun status(state: String) = CallStatusDto(
        callId = "call-1",
        pairId = "pair-1",
        state = state,
        recordingStatus = "idle",
    )
}
