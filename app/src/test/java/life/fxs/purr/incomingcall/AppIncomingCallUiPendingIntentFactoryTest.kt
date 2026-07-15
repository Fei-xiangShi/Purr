package life.fxs.purr.incomingcall

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.domain.incomingcall.IncomingCallReminderContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIncomingCallUiPendingIntentFactoryTest {
    @Test
    fun `Telecom answer pending intent opens exact call on Android 15`() {
        val application = RuntimeEnvironment.getApplication()
        val factory = AppIncomingCallUiPendingIntentFactory(application)

        factory.launchAnswer(
            IncomingCallReminderContent(
                callId = "call-1",
                pairId = "pair-1",
                callerName = "Partner",
                callerAvatarUrl = null,
                startedAtEpochMillis = 1L,
            ),
        )

        val startedIntent = shadowOf(application).nextStartedActivity
        assertThat(startedIntent.action).isEqualTo(IncomingCallActivityContract.ACTION_ANSWER)
        assertThat(startedIntent.getStringExtra(IncomingCallActivityContract.EXTRA_CALL_ID))
            .isEqualTo("call-1")
    }
}
