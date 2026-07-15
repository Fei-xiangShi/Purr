package life.fxs.purr.incomingcall

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class IncomingCallActivityRequestTest {
    @Test
    fun `answer action creates an immediate accept request`() {
        val request = IncomingCallActivityRequest.from(
            Intent(IncomingCallActivityContract.ACTION_ANSWER).apply {
                putExtra(IncomingCallActivityContract.EXTRA_CALL_ID, "call-1")
                putExtra(IncomingCallActivityContract.EXTRA_CALLER_NAME, "Partner")
                putExtra(IncomingCallActivityContract.EXTRA_CALLER_AVATAR_URL, "https://example.test/avatar")
            },
        )

        assertThat(request).isEqualTo(
            IncomingCallActivityRequest.Valid(
                callId = "call-1",
                callerName = "Partner",
                callerAvatarUrl = "https://example.test/avatar",
                acceptImmediately = true,
            ),
        )
    }

    @Test
    fun `unknown action is rejected`() {
        val request = IncomingCallActivityRequest.from(
            Intent("untrusted.action").putExtra(IncomingCallActivityContract.EXTRA_CALL_ID, "call-1"),
        )

        assertThat(request).isEqualTo(IncomingCallActivityRequest.Invalid)
    }
}
