package life.fxs.purr.data.call.remote

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallStatusDto
import org.junit.Test

class CallStatusRemoteDataSourceTest {
    private val api = mockk<PurrCallApi>()

    @Test
    fun `polling retries transient failures and stops after ended`() = runTest {
        var requestCount = 0
        coEvery { api.getCall("call-1") } coAnswers {
            requestCount += 1
            when (requestCount) {
                1 -> throw IOException("temporary")
                2 -> status("active")
                else -> status("ended")
            }
        }
        val source = ApiCallStatusRemoteDataSource(api)

        val states = source.observeStatus("call-1").toList().map { it.state }

        assertThat(states).containsExactly("active", "ended").inOrder()
        assertThat(requestCount).isEqualTo(3)
    }

    private fun status(state: String) = CallStatusDto(
        callId = "call-1",
        pairId = "pair-1",
        state = state,
        recordingStatus = "idle",
        startedAtEpochMillis = 10_000L,
    )
}
